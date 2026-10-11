package com.sunnatbek.villagerciv.sim;

/** Kinds of productive work, each tied to a kind of work site. */
public enum JobType {
	/** Tending a field: one field feeds many, so it takes several hands. */
	FARM(Commodity.FOOD, Role.FARMER, 3, 600),
	/** Planting and felling a grove. */
	LUMBER(Commodity.WOOD, Role.LUMBERJACK, 2, 4_000),
	/** Driving a stair shaft down into the rock: one face, one miner. */
	MINE(Commodity.STONE, Role.MINER, 1, 1_500),
	/**
	 * Building whatever is under construction. It has no product and no work site of its own: its
	 * "site" is the building, and its unseen rate is in construction steps rather than goods.
	 */
	BUILD(null, Role.BUILDER, 2, 40_000),
	/**
	 * Filling the holes in the settlement's ground. No product and no site either: the work is
	 * wherever the ground needs it, and there is none of it to account for while nobody is near.
	 */
	LEVEL(null, Role.LEVELLER, 2, 0),
	/** Working the forge in the smithy. What it yields is not goods but better, cheaper tools and armour. */
	SMITH(null, Role.BLACKSMITH, 1, 0),
	/** Cutting stone in the mason's yard, which is what lets the builders work faster. */
	MASON(null, Role.MASON, 1, 0),
	/** Keeping the market. What it yields is dealt with in {@link Trade}. */
	TRADE(null, Role.MERCHANT, 1, 0),
	/**
	 * Keeping the animals in one of the settlement's pens: cattle in one, sheep in another, and so
	 * on. Its place is the pen, one herder to each. The pens come with the leave to grow beyond
	 * the first wall.
	 */
	HERD(Commodity.FOOD, Role.HERDER, 1, 800);

	/** Id of the design of the workshop this job is done in, or null for a job that has none. */
	public String workshop() {
		return switch (this) {
			case SMITH -> com.sunnatbek.villagerciv.build.Blueprint.SMITHY;
			case MASON -> com.sunnatbek.villagerciv.build.Blueprint.MASONS_YARD;
			case TRADE -> com.sunnatbek.villagerciv.build.Blueprint.MARKET;
			default -> null;
		};
	}

	private final Commodity product;
	private final Role affinity;
	private final int slotsPerSite;
	private final int abstractRate;

	JobType(Commodity product, Role affinity, int slotsPerSite, int abstractRate) {
		this.product = product;
		this.affinity = affinity;
		this.slotsPerSite = slotsPerSite;
		this.abstractRate = abstractRate;
	}

	/** What the job brings in, or null for a job that makes something other than goods. */
	public Commodity product() {
		return product;
	}

	/** True for jobs done at a {@link WorkSite}. */
	public boolean hasWorkSite() {
		return this == FARM || this == LUMBER || this == MINE;
	}

	/** The role that does this job best. */
	public Role affinity() {
		return affinity;
	}

	public int slotsPerSite() {
		return slotsPerSite;
	}

	/**
	 * Thousandths of a unit one suited worker produces per labor cycle while nobody is watching
	 * (the citizen has no body and the work is only accounted for, not acted out).
	 */
	public int abstractRate() {
		return abstractRate;
	}
}
