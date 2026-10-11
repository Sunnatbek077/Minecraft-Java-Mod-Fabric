package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.EnumSet;

/**
 * At nightfall a citizen with a home walks to it and goes in: doors open for citizens and close
 * behind them, and monsters cannot open them. Standing at the foot of its own bed, it lies down
 * and sleeps until morning. The General and the guards do not go in: someone keeps watch.
 */
final class GoHomeGoal extends Goal {
	/**
	 * Close enough to count as "by the bed". A walker stops a little short of the exact spot, so
	 * this is more than one block; it is still less than the two blocks from the nearest point
	 * outside the wall, so it cannot be met from out there.
	 */
	private static final double ARRIVED_SQR = 1.5 * 1.5;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 600;
	private static final int RETRY_WAIT_TICKS = 200;
	/** After a bed turned out unusable, stay standing for this long before trying it again. */
	private static final int BED_RETRY_WAIT_TICKS = 1_200;

	private final CitizenEntity entity;
	private BlockPos bedside;
	private boolean done;
	private int travelled;
	private int repathIn;
	private int waitUntilTick;

	GoHomeGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE));
	}

	/** Where this citizen should be tonight, or null if it has nowhere to go (or it is day). */
	static BlockPos nightPlace(CitizenEntity entity) {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (settlement == null || citizen == null || embodiment == null || WorkDay.isDay(entity.level())
				|| citizen.isDefender() || citizen.home() == Citizen.NO_HOME) {
			return null;
		}
		return embodiment.buildSites().bedsideOf(settlement, citizen.home(), citizen.bed());
	}

	private double distanceSqr() {
		return entity.distanceToSqr(bedside.getX() + 0.5, bedside.getY(), bedside.getZ() + 0.5);
	}

	@Override
	public boolean canUse() {
		if (entity.tickCount < waitUntilTick || entity.isSleeping()) {
			return false;
		}
		// The day's takings go to the storehouse before bed.
		if (WorkDay.isEvening(entity.level()) && (HaulGoal.chestToEmpty(entity) != null || HaulGoal.carriesAtDayEnd(entity))) {
			return false;
		}
		bedside = nightPlace(entity);
		return bedside != null;
	}

	@Override
	public boolean canContinueToUse() {
		return bedside != null && !done && !WorkDay.isDay(entity.level());
	}

	@Override
	public void start() {
		done = false;
		travelled = 0;
		repathIn = 0;
	}

	@Override
	public void stop() {
		entity.getNavigation().stop();
		bedside = null;
		entity.citizen().setActivity(Activity.IDLE);
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (embodiment == null) {
			return;
		}
		entity.citizen().setActivity(Activity.GOING_HOME);
		if (distanceSqr() <= ARRIVED_SQR) {
			entity.getNavigation().stop();
			done = true;
			if (!lieDown(embodiment)) {
				waitUntilTick = entity.tickCount + BED_RETRY_WAIT_TICKS;
			}
		} else if (++travelled > TRAVEL_TIMEOUT_TICKS) {
			// Cannot get in right now; try again in a while rather than push at the wall all night.
			waitUntilTick = entity.tickCount + RETRY_WAIT_TICKS;
			done = true;
		} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
			repathIn = REPATH_INTERVAL_TICKS;
			entity.getNavigation().moveTo(bedside.getX() + 0.5, bedside.getY(), bedside.getZ() + 0.5, 0.6);
		}
	}

	/**
	 * Lies down in the citizen's own bed. Only into a real, whole, free bed, and only if the
	 * citizen really ends up in it; otherwise it stays standing indoors, which is just as safe.
	 */
	private boolean lieDown(EmbodimentController embodiment) {
		Citizen citizen = entity.citizen();
		BlockPos head = embodiment.buildSites().bedHeadOf(entity.settlement(), citizen.home(), citizen.bed());
		if (head == null) {
			return false;
		}
		// Beds are handed out one to a citizen, so the bed's own "occupied" flag is not consulted:
		// it can be left set by a sleeper that vanished, and would then lock the bed for good.
		BlockState state = entity.level().getBlockState(head);
		if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.HEAD) {
			return false;
		}
		// Forget where it was walking to: nothing may pull it back out of bed.
		entity.getNavigation().stop();
		entity.getMoveControl().setWait();
		entity.setTarget(null);
		if (!entity.startSleeping(head)) {
			return false;
		}
		// If it did not end up in the bed after all, better awake on its feet than asleep elsewhere.
		if (entity.distanceToSqr(head.getX() + 0.5, head.getY() + 0.5, head.getZ() + 0.5) > 1.5 * 1.5) {
			entity.stopSleeping();
			return false;
		}
		return true;
	}
}
