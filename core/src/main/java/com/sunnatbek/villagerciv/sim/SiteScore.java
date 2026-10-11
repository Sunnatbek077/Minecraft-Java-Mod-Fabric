package com.sunnatbek.villagerciv.sim;

/**
 * The Leader's judgement of a place, each aspect 0–100. It informs the player; the choice stays
 * theirs, unless the place fails outright ({@link #problem} is set).
 */
public record SiteScore(int total, int water, int wood, int stone, int flatness, int fertility, int defense, Problem problem) {
	public static final SiteScore UNKNOWN = new SiteScore(0, 0, 0, 0, 0, 0, 0, Problem.NONE);

	/** Reasons a place cannot be settled at all. */
	public enum Problem {
		NONE,
		/** The chosen spot itself is water, lava or not natural ground. */
		NOT_ON_LAND,
		/** Too little dry, level ground around the spot for even the first buildings. */
		NO_ROOM,
		/** Too much of the area was not loaded to judge it. */
		NOT_SURVEYED
	}

	public boolean canSettle() {
		return problem == Problem.NONE;
	}
}
