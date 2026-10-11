package com.sunnatbek.villagerciv.scheduler;

/**
 * A unit of simulation work. Tasks are cooperative: long work must check
 * {@link TickContext#hasBudget()} and return {@link TaskResult#CONTINUE} to be resumed later.
 */
@FunctionalInterface
public interface SimTask {
	TaskResult run(TickContext context);
}
