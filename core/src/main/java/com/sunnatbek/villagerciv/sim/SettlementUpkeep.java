package com.sunnatbek.villagerciv.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Hunger in a settlement: every citizen eats one meal a game day from the stockpile. When the
 * food runs out nothing stops at once; loyalty wears away and, in the end, people leave.
 */
public final class SettlementUpkeep {
	private static final long STEP_TICKS = ExpeditionUpkeep.STEP_TICKS;
	private static final int STEPS_PER_DAY = (int) (ExpeditionUpkeep.TICKS_PER_DAY / STEP_TICKS);
	private static final int MAX_CATCH_UP_STEPS = 4;
	private static final int CONTENT_LOYALTY = 70;

	private SettlementUpkeep() {
	}

	/**
	 * Advances the settlement to {@code gameTick}.
	 *
	 * @return members who lost all loyalty and leave; the caller removes them
	 */
	static List<Citizen> update(Settlement settlement, long gameTick) {
		long behind = (gameTick - settlement.lastUpkeepTick()) / STEP_TICKS;
		if (behind <= 0) {
			return List.of();
		}
		if (behind > MAX_CATCH_UP_STEPS) {
			settlement.setLastUpkeepTick(gameTick - MAX_CATCH_UP_STEPS * STEP_TICKS);
			behind = MAX_CATCH_UP_STEPS;
		}
		for (long i = 0; i < behind; i++) {
			settlement.setLastUpkeepTick(settlement.lastUpkeepTick() + STEP_TICKS);
			step(settlement, settlement.lastUpkeepTick() / STEP_TICKS % STEPS_PER_DAY == 0);
		}

		List<Citizen> leaving = new ArrayList<>(0);
		for (Citizen citizen : settlement.members()) {
			if (citizen.loyalty() <= 0) {
				leaving.add(citizen);
			}
		}
		return leaving;
	}

	private static void step(Settlement settlement, boolean newDay) {
		Ledger ledger = settlement.ledger();
		// A child eats half of what a grown citizen does; hunger is counted in twentieths of a meal.
		int hunger = settlement.hunger() + 2 * settlement.size() - settlement.children();
		int due = hunger / (2 * STEPS_PER_DAY);
		// The strategic reserve is not held back from the hungry: only what projects reserved is.
		int eaten = Math.min(due, ledger.available(Commodity.FOOD));
		if (eaten > 0) {
			ledger.take(Commodity.FOOD, eaten);
		}
		settlement.setHunger(Math.min(hunger - eaten * 2 * STEPS_PER_DAY, settlement.size() * 2 * STEPS_PER_DAY));
		boolean starving = eaten < due;
		settlement.setStarving(starving);

		for (Citizen citizen : settlement.members()) {
			if (!starving) {
				citizen.recover(ExpeditionUpkeep.HEAL_PER_STEP);
			}
			if (starving) {
				citizen.setLoyalty(citizen.loyalty() - 1);
			} else if (newDay && citizen.loyalty() < CONTENT_LOYALTY) {
				citizen.setLoyalty(citizen.loyalty() + 1);
			}
		}
	}
}
