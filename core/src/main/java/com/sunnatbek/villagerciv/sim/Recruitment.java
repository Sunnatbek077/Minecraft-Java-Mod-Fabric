package com.sunnatbek.villagerciv.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Rules for asking a villager to join: the villager may say no. */
public final class Recruitment {
	/** Largest group that can travel with one player. */
	public static final int MAX_EXPEDITION_SIZE = 8;
	/** After a refusal the same villager cannot be asked again for one game day. */
	public static final long REFUSAL_COOLDOWN_TICKS = 24_000;

	/** Meals every recruit brings along. */
	public static final int STARTING_MEALS_PER_RECRUIT = 3;

	/**
	 * What the player is told before offering the scroll.
	 *
	 * @param chance        probability that the villager accepts, 0–1
	 * @param cooldownTicks remaining wait after an earlier refusal, 0 if none
	 * @param size          current size of the player's expedition
	 */
	public record Offer(double chance, boolean unemployed, boolean night, long cooldownTicks, int size) {
		public boolean full() {
			return size >= MAX_EXPEDITION_SIZE;
		}
	}

	public sealed interface Result {
	}

	/** @param group the player's expedition, or their settlement if the villager was asked on its land */
	public record Accepted(CitizenGroup group, Citizen citizen) implements Result {
	}

	/** @param retryInTicks 0 for a fresh refusal, otherwise the remaining cooldown */
	public record Refused(long retryInTicks) implements Result {
	}

	public record ExpeditionFull() implements Result {
	}

	// Not saved: after a restart a villager can simply be asked again.
	private final Map<UUID, Long> cooldownUntil = new HashMap<>();

	/**
	 * Chance that a villager accepts.
	 *
	 * @param unemployed no profession, or a nitwit: little to leave behind
	 * @param night      villagers are anxious at night
	 */
	public static double acceptChance(boolean unemployed, boolean night) {
		double chance = 0.6;
		if (unemployed) {
			chance += 0.25;
		}
		if (night) {
			chance -= 0.2;
		}
		return Math.clamp(chance, 0.05, 0.95);
	}

	long cooldownRemaining(UUID villager, long gameTick) {
		Long until = cooldownUntil.get(villager);
		if (until == null) {
			return 0;
		}
		if (gameTick >= until) {
			cooldownUntil.remove(villager);
			return 0;
		}
		return until - gameTick;
	}

	void startCooldown(UUID villager, long gameTick) {
		if (cooldownUntil.size() > 512) {
			cooldownUntil.values().removeIf(until -> gameTick >= until);
		}
		cooldownUntil.put(villager, gameTick + REFUSAL_COOLDOWN_TICKS);
	}
}
