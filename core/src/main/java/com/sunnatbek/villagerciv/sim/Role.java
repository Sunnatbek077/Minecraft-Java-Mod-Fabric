package com.sunnatbek.villagerciv.sim;

/** What a citizen does. A role is an affinity, not a lock: anyone can take any job, less well. */
public enum Role {
	LEADER,
	GENERAL,
	FARMER,
	LUMBERJACK,
	MINER,
	BUILDER,
	LABORER,
	/** Not yet grown: eats, sleeps, and does no work. */
	CHILD,
	/** Keeps the settlement's ground: fills pits and hollows. Nobody is born to it; it is only ever a job. */
	LEVELLER,
	/** Works the forge: tools and armour come quicker, cheaper and better from one who knows the trade. */
	BLACKSMITH,
	/** Cuts stone for the builders. */
	MASON,
	/** Keeps the market: trades what the settlement has too much of for what it is short of. */
	MERCHANT,
	/** Keeps the animals: breeds them in the settlement's pens and brings in what they yield. */
	HERDER;

	/** The role a vanilla villager profession (e.g. {@code "farmer"}) leans towards. */
	public static Role affinityOfProfession(String profession) {
		return switch (profession) {
			case "farmer", "fisherman" -> FARMER;
			case "shepherd", "butcher", "leatherworker" -> HERDER;
			case "fletcher" -> LUMBERJACK;
			case "toolsmith" -> BLACKSMITH;
			case "mason" -> MASON;
			case "librarian", "cartographer", "cleric" -> BUILDER;
			case "armorer", "weaponsmith" -> GENERAL;
			default -> LABORER;
		};
	}
}
