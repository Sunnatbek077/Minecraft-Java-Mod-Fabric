package com.sunnatbek.villagerciv.sim;

/**
 * A Development Plan: a direction the patron sets for a settlement. A plan is not a list of
 * orders; it shifts what the settlement leans towards and is good at.
 *
 * <p>A settlement can take up several plans. A plan followed on its own has a cost, the price of
 * specialising; a settlement that follows more of them grows stronger on every side, each plan
 * making up for what another would have cost. See {@link Settlement#workBonusPercent} and its
 * neighbours for how plans combine.
 */
public enum Policy {
	/** Stands for "no plan at all" where one value has to be shown; never among a settlement's plans. */
	NONE,
	/** Food and people first: better farming and more children. */
	AGRARIAN,
	/** Wood, stone and ore first: better mining and felling, less room kept for growth. */
	INDUSTRY,
	/** Strength first: a harder-hitting General and steadier nerves, fewer children. */
	MILITARY;

	/** Extra work speed, in percent, this plan gives a job. */
	public int workBonusPercent(JobType job) {
		return switch (this) {
			case AGRARIAN -> job == JobType.FARM ? 15 : 0;
			case INDUSTRY -> job == JobType.MINE || job == JobType.LUMBER ? 15 : 0;
			default -> 0;
		};
	}

	/** How readily children are born under this plan, in percent of normal. */
	public int birthPercent() {
		return switch (this) {
			case AGRARIAN -> 125;
			case MILITARY -> 80;
			default -> 100;
		};
	}

	/** Spare beds a thriving settlement keeps ahead of its numbers. */
	public int growthRoom() {
		return this == INDUSTRY ? 2 : 4;
	}
}
