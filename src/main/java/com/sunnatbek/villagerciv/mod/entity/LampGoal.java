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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Putting up lamps where the settlement is dim. It is builders' work: a builder with no building
 * to see to goes round with the lamps, and so does one who was taken on for just that. Like
 * levelling, it is also something a citizen with nothing to do for the moment lends a hand with.
 */
final class LampGoal extends Goal {
	private static final double REACH_SQR = 3.0 * 3.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 400;
	private static final int BUILD_TICKS = 40;
	private static final int LOOK_AGAIN_TICKS = 200;

	private final CitizenEntity entity;
	private final boolean trade;
	private BlockPos spot;
	private boolean finished;
	private int timer;
	private int travelled;
	private int repathIn;
	private int waitUntilTick;

	/** @param trade true for a builder's own round, false for the spare-time kind */
	LampGoal(CitizenEntity entity, boolean trade) {
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
		// A builder with a building in hand has its own goal, which ranks beside this one and is
		// listed first; this one gets a builder only when that has nothing for it.
		boolean builder = citizen.job() == JobType.BUILD;
		if (trade ? !builder : builder || (citizen.job() != null && citizen.activity() != Activity.WAITING)) {
			return false;
		}
		spot = embodiment.lamplighter().take(level, settlement, entity.blockPosition());
		if (spot == null) {
			waitUntilTick = entity.tickCount + LOOK_AGAIN_TICKS;
		}
		return spot != null;
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
		spot = null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		Settlement settlement = entity.settlement();
		if (embodiment == null || settlement == null || spot == null || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		if (entity.distanceToSqr(spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5) <= REACH_SQR) {
			entity.getNavigation().stop();
			entity.citizen().setActivity(Activity.BUILDING);
			entity.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 2, spot.getZ() + 0.5);
			if (++timer < BUILD_TICKS) {
				return;
			}
			embodiment.lamplighter().light(level, settlement, spot);
			entity.playSound(SoundEvents.LANTERN_PLACE, 1.0f, 1.0f);
			if (trade) {
				Labor.reportProgress(entity.citizen(), embodiment.gameTick());
			}
			finished = true; // one lamp, then a fresh look: the next dim spot is somewhere else by now
			return;
		}
		entity.citizen().setActivity(Activity.WALKING);
		if (++travelled > TRAVEL_TIMEOUT_TICKS) {
			finished = true;
			waitUntilTick = entity.tickCount + LOOK_AGAIN_TICKS;
			return;
		}
		if (--repathIn <= 0 && embodiment.takePathSlot()) {
			repathIn = REPATH_INTERVAL_TICKS;
			entity.getNavigation().moveTo(spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, 0.6);
		}
	}
}
