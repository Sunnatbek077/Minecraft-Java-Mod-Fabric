package com.sunnatbek.villagerciv.scheduler;

/** Hardware presets: how much of the machine the mod is allowed to use. */
public enum QualityPreset {
	LOW(40, 1_500_000L, 1),
	MEDIUM(80, 2_500_000L, 2),
	HIGH(150, 4_000_000L, 2);

	private static final long GIB = 1024L * 1024L * 1024L;

	private final int entityBudget;
	private final long tickBudgetNanos;
	private final int workerThreads;

	QualityPreset(int entityBudget, long tickBudgetNanos, int workerThreads) {
		this.entityBudget = entityBudget;
		this.tickBudgetNanos = tickBudgetNanos;
		this.workerThreads = workerThreads;
	}

	public int entityBudget() {
		return entityBudget;
	}

	public long tickBudgetNanos() {
		return tickBudgetNanos;
	}

	public int workerThreads() {
		return workerThreads;
	}

	/** Suggests a preset from the core count and the heap given to Minecraft. */
	public static QualityPreset suggest(int cores, long maxHeapBytes) {
		if (cores >= 8 && maxHeapBytes >= 8 * GIB) {
			return HIGH;
		}
		if (cores >= 6 && maxHeapBytes >= 6 * GIB) {
			return MEDIUM;
		}
		return LOW;
	}
}
