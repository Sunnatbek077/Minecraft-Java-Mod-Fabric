package com.sunnatbek.villagerciv.sim;

/** Rules for turning an expedition into a settlement. */
public final class Founding {
	/** Fewer founders than this cannot start a settlement. */
	public static final int MIN_FOUNDERS = 4;
	/** Radius of the site survey, and of the first territory claim, in blocks. */
	public static final int SURVEY_RADIUS = 48;
	public static final int CLAIM_RADIUS = 40;
	/** How far the survey and the claim of an enlarged settlement reach. */
	public static final int EXPANDED_RADIUS = 96;

	/** How far from its Town Center a settlement's land reaches once it has been enlarged so many times: twice as far the second time. */
	public static int expandedRadius(int expansion) {
		return EXPANDED_RADIUS * Math.max(1, expansion);
	}

	private static final String[] FIRST = {
			"Alder", "Ash", "Birch", "Bram", "Clear", "Elm", "Fern", "Glen", "Green", "Hazel",
			"High", "Mill", "Moss", "Oak", "Red", "River", "Stone", "Thorn", "West", "Willow"
	};
	private static final String[] SECOND = {
			"bridge", "brook", "bury", "dale", "field", "ford", "gate", "ham", "haven", "hollow",
			"marsh", "mere", "mill", "moor", "rest", "stead", "ton", "vale", "watch", "wick"
	};

	/** Why an expedition may not found a settlement at a place. */
	public enum Refusal {
		NONE,
		NO_EXPEDITION,
		TOO_FEW_FOUNDERS,
		NOT_TRAVELLING,
		TERRITORY_TAKEN,
		BAD_SITE
	}

	private Founding() {
	}

	static String settlementName(IdGenerator random) {
		return FIRST[random.nextInt(FIRST.length)] + SECOND[random.nextInt(SECOND.length)];
	}
}
