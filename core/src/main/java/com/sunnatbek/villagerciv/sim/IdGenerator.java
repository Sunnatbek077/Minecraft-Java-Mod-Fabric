package com.sunnatbek.villagerciv.sim;

import java.util.UUID;

/**
 * Deterministic source of ids and random rolls: the same seed and call order always yield the
 * same values, so a headless run can be replayed exactly. Seed and counter are saved with the
 * world.
 */
public final class IdGenerator {
	private final long seed;
	private long counter;
	private boolean dirty;

	public IdGenerator(long seed, long counter) {
		this.seed = seed;
		this.counter = counter;
	}

	public UUID next() {
		long n = advance();
		return new UUID(mix(seed ^ (2 * n + 1)), mix(seed + 0x9E3779B97F4A7C15L * (2 * n + 2)));
	}

	/** Uniform roll in [0, 1). */
	public double nextDouble() {
		return (mix(seed ^ (2 * advance() + 1)) >>> 11) * 0x1.0p-53;
	}

	public int nextInt(int bound) {
		return (int) (nextDouble() * bound);
	}

	private long advance() {
		dirty = true;
		return counter++;
	}

	public long seed() {
		return seed;
	}

	public long counter() {
		return counter;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void markClean() {
		dirty = false;
	}

	// SplitMix64 finalizer
	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
