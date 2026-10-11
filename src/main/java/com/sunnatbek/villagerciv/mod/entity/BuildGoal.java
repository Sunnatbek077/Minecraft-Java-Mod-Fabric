package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.ConstructionPlan;
import com.sunnatbek.villagerciv.mod.BuildSites;
import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.ToolKind;
import com.sunnatbek.villagerciv.sim.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * A builder at work: it walks to the front of the building under construction and adds to it
 * step by step, so the player sees it rise block by block. The builder stays on the verge and
 * never steps inside, where a wall could close around it.
 */
final class BuildGoal extends Goal {
	private static final double REACH_SQR = 4.0 * 4.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 400;
	private static final int RETRY_WAIT_TICKS = 100;
	/** Steps that change nothing take no time; this many may be passed over in one tick. */
	private static final int MAX_SKIPS_PER_TICK = 32;

	private final CitizenEntity entity;
	private BlockPos stand;
	private int timer;
	private int travelled;
	private int repathIn;
	private int waitUntilTick;
	/** Reckoned work on a site that is not loaded: as many steps, as often, as a builder nobody is watching does. */
	private static final int UNSEEN_INTERVAL_TICKS = 20;
	private static final int UNSEEN_STEPS = 4;
	private boolean finished;

	BuildGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private Building project() {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		if (settlement == null || citizen.job() != JobType.BUILD || !WorkDay.isDay(entity.level())) {
			return null;
		}
		Building building = settlement.building(citizen.jobSite());
		return building != null && building.stage() == Building.Stage.UNDER_CONSTRUCTION && !building.isWorkDone() ? building : null;
	}

	@Override
	public boolean canUse() {
		return entity.tickCount >= waitUntilTick && project() != null;
	}

	@Override
	public boolean canContinueToUse() {
		return !finished && project() != null;
	}

	@Override
	public void start() {
		trace("starts");
		finished = false;
		stand = null;
		timer = 0;
		travelled = 0;
		repathIn = 0;
	}

	@Override
	public void stop() {
		trace("stops: finished=" + finished + " project=" + (project() != null));
		entity.getNavigation().stop();
		entity.citizen().setActivity(Activity.IDLE);
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		Building building = project();
		if (embodiment == null || building == null || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		BuildSites sites = embodiment.buildSites();
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		if (!sites.isAlive(level, building)) {
			// The site lies beyond where the game is running things: the far side of a wide
			// wall, say. The work goes on all the same, reckoned as it is when nobody is near,
			// and shows in the world when someone comes that way. The builder stays where it is.
			stand = null;
			entity.getNavigation().stop();
			citizen.setActivity(Activity.AT_WORK);
			if (--timer <= 0) {
				timer = UNSEEN_INTERVAL_TICKS;
				building.advance(Math.max(1, UNSEEN_STEPS * Labor.efficiencyPercent(settlement, citizen, JobType.BUILD) / 100));
				Labor.reportProgress(citizen, embodiment.gameTick());
			}
			return;
		}
		if (stand == null) {
			stand = sites.standPoint(level, building);
		}

		boolean there = entity.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) <= REACH_SQR;
		if (entity.tickCount % 100 == 0) {
			trace("on #" + building.id() + " there=" + there + " travelled=" + travelled + " timer=" + timer + " stand=" + stand.toShortString());
		}
		citizen.setActivity(there ? Activity.BUILDING : Activity.WALKING);
		if (!there) {
			if (++travelled > TRAVEL_TIMEOUT_TICKS) {
				// Cannot find the way to its own building site: it is set down there.
				trace("set down at site #" + building.id() + " " + stand.toShortString());
				entity.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				travelled = 0;
			} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
			}
			return;
		}
		entity.getNavigation().stop();
		entity.getLookControl().setLookAt(building.x() + building.sizeX() / 2.0, entity.getEyeY() + 1.0, building.z() + building.sizeZ() / 2.0);
		if (--timer > 0) {
			return;
		}

		// Do the next step that actually changes something; the ones that do not are free.
		for (int i = 0; i < MAX_SKIPS_PER_TICK; i++) {
			ConstructionPlan.Kind kind = sites.nextKind(settlement, building);
			if (kind == null) {
				break;
			}
			BuildSites.Outcome outcome = sites.applyNext(level, settlement, building);
			if (outcome == BuildSites.Outcome.NOT_NOW) {
				trace("cannot apply " + kind + " at #" + building.id());
				pause();
				return;
			}
			if (outcome == BuildSites.Outcome.CHANGED) {
				timer = BuildSites.ticksFor(kind) * 100 / Labor.efficiencyPercent(settlement, citizen, JobType.BUILD);
				// Earthwork is shovel work.
				if (kind != ConstructionPlan.Kind.PLACE && Tools.equipped(settlement, citizen, ToolKind.SHOVEL)) {
					timer = timer * 100 / Tools.SHOVEL_SPEED_PERCENT;
				}
				break;
			}
		}
		Labor.reportProgress(citizen, embodiment.gameTick());
	}

	/** A line in the log for the unattended test run; nothing otherwise. */
	private void trace(String what) {
		if (Long.getLong("villagerciv.autotest", 0L) > 0) {
			VillagerCivMod.LOGGER.info("AUTOTEST   builder {} at {}: {}", entity.citizen().name(), entity.blockPosition().toShortString(), what);
		}
	}

	private void pause() {
		finished = true;
		waitUntilTick = entity.tickCount + RETRY_WAIT_TICKS;
	}
}
