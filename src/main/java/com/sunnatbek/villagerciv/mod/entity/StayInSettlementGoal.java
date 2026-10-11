package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** A settled citizen that strayed too far from the Town Center walks back to it. */
final class StayInSettlementGoal extends Goal {
	/** Work sites lie further out than this, so by day a citizen with a job is left to it. */
	private static final double MAX_DISTANCE_SQR = 24.0 * 24.0;
	/** At night everyone comes in close to the light of the Town Center. */
	private static final double NIGHT_DISTANCE_SQR = 10.0 * 10.0;
	private static final double WATCH_DISTANCE_SQR = 44.0 * 44.0;
	private static final double HOME_DISTANCE_SQR = 8.0 * 8.0;

	private final CitizenEntity citizen;

	StayInSettlementGoal(CitizenEntity citizen) {
		this.citizen = citizen;
		setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		// Whoever has a home goes there at night instead of to the Town Center.
		if (GoHomeGoal.nightPlace(citizen) != null) {
			return false;
		}
		boolean night = !WorkDay.isDay(citizen.level());
		if (!night && citizen.citizen() != null && citizen.citizen().job() != null) {
			return false;
		}
		// Those on watch walk the whole place at night; everyone else comes in close.
		boolean onWatch = night && citizen.citizen() != null && citizen.citizen().isDefender();
		return distanceToCenterSqr() > (onWatch ? WATCH_DISTANCE_SQR : night ? NIGHT_DISTANCE_SQR : MAX_DISTANCE_SQR);
	}

	@Override
	public boolean canContinueToUse() {
		return !citizen.getNavigation().isDone() && distanceToCenterSqr() > HOME_DISTANCE_SQR;
	}

	@Override
	public void start() {
		Settlement settlement = citizen.settlement();
		citizen.getNavigation().moveTo(settlement.anchorX() + 0.5, settlement.anchorY(), settlement.anchorZ() + 0.5, 0.6);
	}

	@Override
	public void stop() {
		citizen.getNavigation().stop();
	}

	/** Zero for a citizen that has no settlement, so the goal never runs for it. */
	private double distanceToCenterSqr() {
		Settlement settlement = citizen.settlement();
		return settlement == null ? 0 : citizen.distanceToSqr(settlement.anchorX() + 0.5, settlement.anchorY(), settlement.anchorZ() + 0.5);
	}
}
