package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.EventBus;
import com.sunnatbek.villagerciv.api.ExpeditionHungryEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily life of a travelling group: it eats from its shared supply, and its trust in the player
 * moves with how it is treated. Hunger and long waits wear loyalty down; a member with none left
 * goes home. Nothing here stops the group outright: neglect slows and shrinks it.
 */
public final class ExpeditionUpkeep {
	public static final long TICKS_PER_DAY = 24_000;
	/** Upkeep runs in tenths of a game day. */
	public static final long STEP_TICKS = TICKS_PER_DAY / 10;
	public static final int BETRAYAL_LOYALTY_LOSS = 20;
	/** Health a fed citizen regains per upkeep step: back from the brink in about half a day. */
	public static final float HEAL_PER_STEP = 4f;

	private static final int STEPS_PER_DAY = (int) (TICKS_PER_DAY / STEP_TICKS);
	private static final int MAX_CATCH_UP_STEPS = 4;
	private static final int CONTENT_LOYALTY = 70;

	private ExpeditionUpkeep() {
	}

	/**
	 * Advances the expedition to {@code gameTick}.
	 *
	 * @return members who lost all loyalty and leave; the caller removes them
	 */
	static List<Citizen> update(Expedition expedition, long gameTick, EventBus events) {
		long behind = (gameTick - expedition.lastUpkeepTick()) / STEP_TICKS;
		if (behind <= 0) {
			return List.of();
		}
		if (behind > MAX_CATCH_UP_STEPS) {
			// A long gap is not replayed step by step.
			expedition.setLastUpkeepTick(gameTick - MAX_CATCH_UP_STEPS * STEP_TICKS);
			behind = MAX_CATCH_UP_STEPS;
		}
		for (long i = 0; i < behind; i++) {
			expedition.setLastUpkeepTick(expedition.lastUpkeepTick() + STEP_TICKS);
			step(expedition, expedition.lastUpkeepTick() / STEP_TICKS, events);
		}

		List<Citizen> deserters = new ArrayList<>(0);
		for (Citizen citizen : expedition.members()) {
			if (citizen.loyalty() <= 0) {
				deserters.add(citizen);
			}
		}
		return deserters;
	}

	private static void step(Expedition expedition, long stepIndex, EventBus events) {
		boolean newDay = stepIndex % STEPS_PER_DAY == 0;

		// Whole numbers only (tenths of a meal), so long runs never drift.
		int hunger = expedition.hunger() + expedition.size();
		int due = hunger / STEPS_PER_DAY;
		int eaten = Math.min(due, expedition.food());
		expedition.setFood(expedition.food() - eaten);
		// Missed meals are not owed forever: at most one per member carries over.
		expedition.setHunger(Math.min(hunger - eaten * STEPS_PER_DAY, expedition.size() * STEPS_PER_DAY));

		boolean starving = eaten < due;
		if (starving && (!expedition.isStarving() || newDay)) {
			// Ask when the food runs out, then remind once a day.
			events.publish(new ExpeditionHungryEvent(expedition.id()));
		}
		expedition.setStarving(starving);

		boolean camped = expedition.state() == Expedition.State.CAMPED;
		for (Citizen citizen : expedition.members()) {
			if (!starving) {
				citizen.recover(HEAL_PER_STEP);
			}
			int change = 0;
			if (starving) {
				change -= 1;
			}
			if (newDay) {
				if (camped) {
					change -= 1;
				} else if (!starving && citizen.loyalty() < CONTENT_LOYALTY) {
					change += 1;
				}
			}
			if (change != 0) {
				citizen.setLoyalty(citizen.loyalty() + change);
			}
		}
	}
}
