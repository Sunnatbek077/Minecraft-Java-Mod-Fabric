package com.sunnatbek.villagerciv.scheduler;

import java.util.function.LongSupplier;

/** Per-tick view handed to tasks. One instance is reused across ticks. */
public final class TickContext {
	private final LongSupplier nanoTime;
	private long gameTick;
	private long startNanos;
	private long budgetNanos;

	TickContext(LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
	}

	void reset(long gameTick, long startNanos, long budgetNanos) {
		this.gameTick = gameTick;
		this.startNanos = startNanos;
		this.budgetNanos = budgetNanos;
	}

	public long gameTick() {
		return gameTick;
	}

	public long elapsedNanos() {
		return nanoTime.getAsLong() - startNanos;
	}

	/** False once the mod's budget for this tick is spent; tasks should yield. */
	public boolean hasBudget() {
		return elapsedNanos() < budgetNanos;
	}
}
