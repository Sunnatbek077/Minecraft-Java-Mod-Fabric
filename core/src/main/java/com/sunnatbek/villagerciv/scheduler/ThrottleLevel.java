package com.sunnatbek.villagerciv.scheduler;

/** Adaptive throttling: the slower the server tick, the less the mod is allowed to do. */
public enum ThrottleLevel {
	NORMAL(1.0, TaskPriority.BACKGROUND),
	REDUCED(0.5, TaskPriority.BACKGROUND),
	CRITICAL(0.25, TaskPriority.EMBODIED);

	public static final double REDUCED_MSPT = 40.0;
	public static final double CRITICAL_MSPT = 50.0;

	private final double budgetFactor;
	private final TaskPriority lowestAllowed;

	ThrottleLevel(double budgetFactor, TaskPriority lowestAllowed) {
		this.budgetFactor = budgetFactor;
		this.lowestAllowed = lowestAllowed;
	}

	public double budgetFactor() {
		return budgetFactor;
	}

	public boolean allows(TaskPriority priority) {
		return priority.ordinal() <= lowestAllowed.ordinal();
	}

	public static ThrottleLevel forMspt(double serverMspt) {
		if (serverMspt > CRITICAL_MSPT) {
			return CRITICAL;
		}
		if (serverMspt > REDUCED_MSPT) {
			return REDUCED;
		}
		return NORMAL;
	}
}
