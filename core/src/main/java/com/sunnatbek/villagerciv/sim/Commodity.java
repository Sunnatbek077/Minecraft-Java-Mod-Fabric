package com.sunnatbek.villagerciv.sim;

/**
 * Planning-level resource classes. The AI reasons about these few classes instead of hundreds of
 * item types; exact items only matter where a blueprint asks for them.
 */
public enum Commodity {
	FOOD,
	WOOD,
	STONE,
	ORE,
	TOOLS
}
