package com.sunnatbek.villagerciv.scheduler;

/**
 * Per-system circuit breaker. A system that keeps overrunning the tick budget (or throws) is
 * suspended for a while, so one misbehaving system cannot freeze the game.
 */
final class CircuitBreaker {
	static final int STRIKES_TO_OPEN = 3;
	static final long COOLDOWN_TICKS = 200;

	private int strikes;
	private long openUntilTick = Long.MIN_VALUE;

	boolean isOpen(long gameTick) {
		return gameTick < openUntilTick;
	}

	/** @return true if this call opened the breaker */
	boolean record(boolean overrun, long gameTick) {
		if (!overrun) {
			strikes = 0;
			return false;
		}
		if (++strikes < STRIKES_TO_OPEN) {
			return false;
		}
		return trip(gameTick);
	}

	boolean trip(long gameTick) {
		strikes = 0;
		openUntilTick = gameTick + COOLDOWN_TICKS;
		return true;
	}
}
