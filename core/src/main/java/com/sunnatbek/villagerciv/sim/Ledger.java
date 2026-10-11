package com.sunnatbek.villagerciv.sim;

/**
 * A settlement's stockpile: the truth about what it owns. Storage blocks in the world are only a
 * window onto these numbers.
 *
 * <p>For every commodity: {@code onHand} is what exists, {@code reserved} is the part promised to
 * projects, and {@code available = onHand - reserved} is what may still be spent. Reservations are
 * what stops two projects from using the same stone twice. Invariant: {@code 0 <= reserved <= onHand}.
 */
public final class Ledger {
	private static final Commodity[] COMMODITIES = Commodity.values();

	private final int[] onHand = new int[COMMODITIES.length];
	private final int[] reserved = new int[COMMODITIES.length];
	private final Runnable onChange;

	Ledger(Runnable onChange) {
		this.onChange = onChange;
	}

	public int onHand(Commodity commodity) {
		return onHand[commodity.ordinal()];
	}

	public int reserved(Commodity commodity) {
		return reserved[commodity.ordinal()];
	}

	public int available(Commodity commodity) {
		return onHand[commodity.ordinal()] - reserved[commodity.ordinal()];
	}

	public void add(Commodity commodity, int amount) {
		requirePositive(amount);
		onHand[commodity.ordinal()] += amount;
		onChange.run();
	}

	/** Spends unreserved stock. Does nothing and returns false if there is not enough of it. */
	public boolean take(Commodity commodity, int amount) {
		requirePositive(amount);
		if (available(commodity) < amount) {
			return false;
		}
		onHand[commodity.ordinal()] -= amount;
		onChange.run();
		return true;
	}

	/** Sets stock aside for a project. Returns false if there is not enough unreserved stock. */
	public boolean reserve(Commodity commodity, int amount) {
		requirePositive(amount);
		if (available(commodity) < amount) {
			return false;
		}
		reserved[commodity.ordinal()] += amount;
		onChange.run();
		return true;
	}

	/** Gives a reservation back without spending it (project cancelled, lease expired). */
	public void release(Commodity commodity, int amount) {
		requirePositive(amount);
		reserved[commodity.ordinal()] -= Math.min(amount, reserved[commodity.ordinal()]);
		onChange.run();
	}

	/** Spends stock that was reserved earlier. Returns false if that much is not reserved. */
	public boolean consumeReserved(Commodity commodity, int amount) {
		requirePositive(amount);
		if (reserved[commodity.ordinal()] < amount) {
			return false;
		}
		reserved[commodity.ordinal()] -= amount;
		onHand[commodity.ordinal()] -= amount;
		onChange.run();
		return true;
	}

	/** Restores saved amounts; anything inconsistent is clamped back into the invariant. */
	public void restore(Commodity commodity, int onHandAmount, int reservedAmount) {
		onHand[commodity.ordinal()] = Math.max(0, onHandAmount);
		reserved[commodity.ordinal()] = Math.clamp(reservedAmount, 0, onHand[commodity.ordinal()]);
	}

	private static void requirePositive(int amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("amount must be positive: " + amount);
		}
	}
}
