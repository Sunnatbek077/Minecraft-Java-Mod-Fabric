package com.sunnatbek.villagerciv.harness;

/** Manually advanced nanosecond clock: tests decide how much time a piece of work "costs". */
public final class FakeClock {
	private long nanos;

	public long nanoTime() {
		return nanos;
	}

	public void advance(long deltaNanos) {
		nanos += deltaNanos;
	}

	public void advanceMillis(double millis) {
		nanos += (long) (millis * 1_000_000);
	}
}
