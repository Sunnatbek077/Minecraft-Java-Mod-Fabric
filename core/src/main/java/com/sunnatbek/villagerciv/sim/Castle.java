package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.build.ShapedWall;

/**
 * The castle a city of eight sides may raise over itself: a stone platform held up by the towers
 * of its great wall, and on it a castle to a design of its own. What it looks like and where each
 * block goes is the platform layer's business; here is only how far the work has come.
 *
 * <p>The work is done by a brigade of builders and is finished in a set time, whatever else the
 * city is about: the patron was promised it in seven days. Stone is taken from the store as the
 * walls go up, one unit for so many blocks, while the store can spare it; when it cannot, the
 * brigade builds on regardless rather than miss the day.
 */
public final class Castle {
	/** How long the castle takes from the day it is begun to the last block. */
	public static final long BUILD_TICKS = 7 * ExpeditionUpkeep.TICKS_PER_DAY;
	/** Builders taken off other work for it, as long as it is going up. */
	public static final int BRIGADE = 24;
	public static final int BLOCKS_PER_STONE = 10;
	private static final int STONE_KEPT_PER_HEAD = 4;

	private Castle() {
	}

	/** Whether a castle may be begun here: an eight-sided city whose great wall is finished, with none yet. */
	public static boolean mayBegin(Settlement settlement) {
		return settlement.stage() == SettlementStage.CITY && settlement.expansion() >= 2 && settlement.wallShape() == ShapedWall.Shape.OCTAGON
				&& settlement.wallPlan().isEmpty() && !settlement.hasCastle();
	}

	/**
	 * Begins the castle.
	 *
	 * @param platformY the height of the top of the platform it stands on
	 * @param blocks    how many blocks the whole of it is, platform and pillars included
	 */
	public static void begin(Settlement settlement, int platformY, int blocks) {
		settlement.setCastle(platformY, blocks, 0);
	}

	public static boolean isBuilding(Settlement settlement) {
		return settlement.hasCastle() && settlement.castleWorked() < BUILD_TICKS;
	}

	public static boolean isFinished(Settlement settlement) {
		return settlement.hasCastle() && settlement.castleWorked() >= BUILD_TICKS;
	}

	/** How many of its blocks stand by now: the same share of them as of the time it takes. */
	public static long built(Settlement settlement) {
		if (!settlement.hasCastle()) {
			return 0;
		}
		long worked = Math.min(BUILD_TICKS, settlement.castleWorked());
		return settlement.castleBlocks() * worked / BUILD_TICKS;
	}

	/** Another stretch of work: the time goes on, and the stone for what was built is taken from the store. */
	static void update(Settlement settlement, long ticks) {
		if (!isBuilding(settlement)) {
			return;
		}
		long before = built(settlement);
		settlement.setCastle(settlement.castleY(), settlement.castleBlocks(), Math.min(BUILD_TICKS, settlement.castleWorked() + ticks));
		long owed = built(settlement) / BLOCKS_PER_STONE - before / BLOCKS_PER_STONE;
		int spare = settlement.ledger().available(Commodity.STONE) - settlement.size() * STONE_KEPT_PER_HEAD;
		int taken = (int) Math.min(owed, Math.max(0, spare));
		if (taken > 0) {
			settlement.ledger().take(Commodity.STONE, taken);
		}
	}
}
