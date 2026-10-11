package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Filling holes in the settlement's ground, a layer of earth at a time. Every citizen has this
 * goal twice. One is for the Leveller, whose job it is. The other is spare time put to use, for
 * anyone with nothing to do at the moment (no job, or a job that is waiting on crops or
 * saplings); that one ranks below real work, so it is dropped the moment there is some.
 */
final class LandscapeGoal extends Goal {
	private static final double REACH_SQR = 3.5 * 3.5;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 400;
	private static final int FILL_TICKS = 20;
	private static final int LOOK_AGAIN_TICKS = 200;

	private final CitizenEntity entity;
	private final boolean trade;
	private BlockPos hole;
	private boolean finished;
	private int timer;
	private int travelled;
	private int repathIn;
	private int waitUntilTick;

	/** @param trade true for the Leveller's own work, false for the spare-time kind */
	LandscapeGoal(CitizenEntity entity, boolean trade) {
		this.entity = entity;
		this.trade = trade;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (entity.tickCount < waitUntilTick || !(entity.level() instanceof ServerLevel level)) {
			return false;
		}
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (settlement == null || citizen == null || embodiment == null || citizen.isChild() || citizen.isDefender()
				|| !WorkDay.isDay(level)) {
			return false;
		}
		boolean leveller = citizen.job() == JobType.LEVEL;
		if (trade ? !leveller : leveller || (citizen.job() != null && citizen.activity() != Activity.WAITING)) {
			return false;
		}
		hole = embodiment.landscaper().take(level, settlement, entity.blockPosition());
		if (hole == null) {
			waitUntilTick = entity.tickCount + LOOK_AGAIN_TICKS;
		}
		return hole != null;
	}

	@Override
	public boolean canContinueToUse() {
		return !finished && WorkDay.isDay(entity.level());
	}

	@Override
	public void start() {
		finished = false;
		timer = 0;
		travelled = 0;
		repathIn = 0;
	}

	@Override
	public void stop() {
		entity.getNavigation().stop();
		entity.citizen().setActivity(Activity.IDLE);
		hole = null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		Settlement settlement = entity.settlement();
		if (embodiment == null || settlement == null || hole == null || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		if (entity.distanceToSqr(hole.getX() + 0.5, hole.getY() + 1, hole.getZ() + 0.5) <= REACH_SQR) {
			entity.getNavigation().stop();
			entity.citizen().setActivity(Activity.WORKING);
			entity.getLookControl().setLookAt(hole.getX() + 0.5, hole.getY() + 1, hole.getZ() + 0.5);
			// A Leveller with a shovel makes short work of it.
			int needed = trade ? FILL_TICKS * 100 / Labor.efficiencyPercent(settlement, entity.citizen(), JobType.LEVEL) : FILL_TICKS;
			if (++timer < needed) {
				return;
			}
			timer = 0;
			embodiment.landscaper().fill(level, settlement, hole);
			if (trade) {
				Labor.reportProgress(entity.citizen(), embodiment.gameTick());
			}
			// On to the next one, the nearest: usually the same hole, a layer higher or a step along.
			hole = embodiment.landscaper().take(level, settlement, entity.blockPosition());
			travelled = 0;
			repathIn = 0;
			if (hole == null) {
				finished = true;
				waitUntilTick = entity.tickCount + LOOK_AGAIN_TICKS;
			}
			return;
		}
		entity.citizen().setActivity(Activity.WALKING);
		if (++travelled > TRAVEL_TIMEOUT_TICKS) {
			finished = true; // cannot get to it; the next look-over will note it again
			return;
		}
		if (--repathIn <= 0 && embodiment.takePathSlot()) {
			repathIn = REPATH_INTERVAL_TICKS;
			entity.getNavigation().moveTo(hole.getX() + 0.5, hole.getY() + 1, hole.getZ() + 0.5, 0.6);
		}
	}
}
