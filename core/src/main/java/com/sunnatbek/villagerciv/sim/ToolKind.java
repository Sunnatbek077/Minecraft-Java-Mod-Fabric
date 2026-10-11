package com.sunnatbek.villagerciv.sim;

/** The kinds of tool a settlement puts to use, and who uses each. */
public enum ToolKind {
	/** Farmers till and reap with it. */
	HOE,
	/** Lumberjacks fell trees with it. */
	AXE,
	/** Miners cut rock with it. */
	PICKAXE,
	/** Anyone who levels ground or digs a well: farmers, lumberjacks and builders. */
	SHOVEL,
	/** The General and the guards fight with it. */
	SWORD;

	/** The tool a job is done with, or null if it has none. */
	public static ToolKind of(JobType job) {
		return switch (job) {
			case FARM -> HOE;
			case LUMBER -> AXE;
			case MINE -> PICKAXE;
			// Builders work with what the settlement has made; only their earthwork has a tool.
			case BUILD -> null;
			case LEVEL -> SHOVEL;
			// The forge and the yard have their own fixed tools.
			case SMITH, MASON, TRADE -> null;
			// Animals are led and fed by hand.
			case HERD -> null;
		};
	}

	/** Whether this citizen, doing what it does now, would put the tool to use. */
	public boolean usedBy(Citizen citizen) {
		JobType job = citizen.job();
		return switch (this) {
			case HOE -> job == JobType.FARM;
			case AXE -> job == JobType.LUMBER;
			case PICKAXE -> job == JobType.MINE;
			case SHOVEL -> job == JobType.FARM || job == JobType.LUMBER || job == JobType.BUILD || job == JobType.LEVEL;
			case SWORD -> citizen.isDefender();
		};
	}
}
