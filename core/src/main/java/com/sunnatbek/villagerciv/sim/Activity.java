package com.sunnatbek.villagerciv.sim;

/**
 * What a citizen is doing at this moment, as far as an onlooker can tell. It exists so the player
 * can see what is going on; nothing is decided by it and it is never saved.
 */
public enum Activity {
	IDLE,
	WALKING,
	WORKING,
	/** Carrying a load to the stockpile. */
	HAULING,
	BUILDING,
	/** Has a job, but there is nothing to do at the moment (crops and saplings are growing). */
	WAITING,
	GOING_HOME,
	SLEEPING,
	/** Night, and no bed to go to. */
	RESTING,
	ON_WATCH,
	/** Travelling with the player. */
	FOLLOWING,
	/** Out of sight: at its work, accounted for rather than acted out. */
	AT_WORK
}
