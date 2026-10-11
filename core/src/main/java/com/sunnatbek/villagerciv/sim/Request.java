package com.sunnatbek.villagerciv.sim;

/**
 * Something a settlement asks its patron for. It is a request, not a demand: it has a reason, it
 * can be met in part, and when its time runs out the settlement simply carries on without.
 *
 * @param purpose what it is for: a blueprint id, or {@link #FOOD_RESERVE}
 */
public record Request(int id, Commodity commodity, int amount, int delivered, String purpose, long createdTick, long deadlineTick) {
	public static final String FOOD_RESERVE = "food_reserve";

	public int remaining() {
		return Math.max(0, amount - delivered);
	}

	Request withDelivered(int delivered) {
		return new Request(id, commodity, amount, delivered, purpose, createdTick, deadlineTick);
	}
}
