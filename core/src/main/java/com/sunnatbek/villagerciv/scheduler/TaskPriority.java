package com.sunnatbek.villagerciv.scheduler;

/** Scheduler queues, highest priority first. */
public enum TaskPriority {
	INTERRUPT,
	EMBODIED,
	GROUP,
	SETTLEMENT_AI,
	VIRTUAL_BATCH,
	CIVILIZATION_AI,
	BACKGROUND
}
