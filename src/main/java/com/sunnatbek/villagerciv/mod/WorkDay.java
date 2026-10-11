package com.sunnatbek.villagerciv.mod;

import net.minecraft.world.level.Level;

/**
 * When citizens work and when they sleep. This goes by the clock, not by how bright the sky is:
 * a rainy or stormy day is still a working day, and nobody goes to bed at noon because of clouds.
 */
public final class WorkDay {
	private static final long DAY_LENGTH = 24_000;
	/**
	 * People go out once the sun is properly up and the night's monsters have burned, and are
	 * home with time to spare before dark: the walk back from the fields must not end at dusk.
	 */
	private static final long WORK_STARTS = 500;
	private static final long WORK_ENDS = 11_500;

	/** For this long after work ends, what was gathered in the day is carried to the storehouse. */
	private static final long HAULING_TIME = 1_500;

	private WorkDay() {
	}

	public static boolean isDay(Level level) {
		long time = Math.floorMod(level.getOverworldClockTime(), DAY_LENGTH);
		return time >= WORK_STARTS && time < WORK_ENDS;
	}

	/** The evening, between work and bed. */
	public static boolean isEvening(Level level) {
		long time = Math.floorMod(level.getOverworldClockTime(), DAY_LENGTH);
		return time >= WORK_ENDS && time < WORK_ENDS + HAULING_TIME;
	}
}
