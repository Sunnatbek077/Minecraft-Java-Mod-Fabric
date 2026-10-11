package com.sunnatbek.villagerciv.scheduler;

public enum TaskResult {
	/** The task is finished (a recurring task waits for its next period). */
	DONE,
	/** The task yielded and wants another slice, this tick or a later one. */
	CONTINUE
}
