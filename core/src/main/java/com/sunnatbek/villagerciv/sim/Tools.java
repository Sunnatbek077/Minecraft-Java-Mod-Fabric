package com.sunnatbek.villagerciv.sim;

/**
 * Who gets which tool. A settlement works without any tools at all, only slower: tools are one of
 * the ways a player can speed it up. They are handed out to those who would use them, in the
 * order the citizens joined, until the store of that kind runs out.
 */
public final class Tools {
	/** Work with the proper tool takes this share of the time: half again as fast. */
	public static final int TOOL_SPEED_PERCENT = 150;
	/** Levelling and digging with a shovel is twice as fast. */
	public static final int SHOVEL_SPEED_PERCENT = 200;

	private Tools() {
	}

	/** Tools from a smith's forge are better than ones knocked together at home. */
	public static final int FORGED_SPEED_PERCENT = 175;

	/** How much faster the proper tool makes its user: more so once the settlement has a smith at work. */
	public static int speedPercent(Settlement settlement) {
		return settlement.hasWorker(JobType.SMITH) ? FORGED_SPEED_PERCENT : TOOL_SPEED_PERCENT;
	}

	/** What goes into one tool: a haft of wood and a head of metal, or of stone where there is no ore to spare. */
	public static final int WOOD_PER_TOOL = 2;
	public static final int HEAD_PER_TOOL = 3;
	private static final int TOOLS_PER_REVIEW = 2;

	/**
	 * A settlement makes the tools it is short of. Nobody has to bring them: whenever someone
	 * could use a tool and the store has none for them, one is made from wood and ore, or from
	 * wood and stone if the ore has run out. A gift of tools still saves it the materials.
	 */
	static void makeMissing(Settlement settlement) {
		Ledger ledger = settlement.ledger();
		int made = 0;
		for (ToolKind kind : ToolKind.values()) {
			for (int missing = needed(settlement, kind) - settlement.tools(kind); missing > 0; missing--) {
				// A smith wastes less metal, and gets through more work.
				boolean smith = settlement.hasWorker(JobType.SMITH);
				int headNeeded = smith ? HEAD_PER_TOOL - 1 : HEAD_PER_TOOL;
				Commodity head = ledger.available(Commodity.ORE) >= headNeeded ? Commodity.ORE : Commodity.STONE;
				// Tools come after roofs: nothing is made while the wood or stone is short of
				// what the next building takes, and only a couple at a time.
				if (made >= (smith ? TOOLS_PER_REVIEW * 3 : TOOLS_PER_REVIEW) || settlement.status(Commodity.WOOD) != StockStatus.OK
						|| (head == Commodity.STONE && settlement.status(Commodity.STONE) != StockStatus.OK)
						|| ledger.available(Commodity.WOOD) < WOOD_PER_TOOL || ledger.available(head) < headNeeded) {
					return;
				}
				ledger.take(Commodity.WOOD, WOOD_PER_TOOL);
				ledger.take(head, headNeeded);
				settlement.makeTools(kind, 1);
				made++;
			}
		}
	}

	/** How many tools of a kind the settlement could put to use right now. */
	public static int needed(Settlement settlement, ToolKind kind) {
		int needed = 0;
		for (Citizen citizen : settlement.members()) {
			if (kind.usedBy(citizen)) {
				needed++;
			}
		}
		return needed;
	}

	/** Whether there is a tool of this kind for this citizen. */
	public static boolean equipped(Settlement settlement, Citizen citizen, ToolKind kind) {
		if (!kind.usedBy(citizen)) {
			return false;
		}
		int available = settlement.tools(kind);
		if (available <= 0) {
			return false;
		}
		int ahead = 0;
		for (Citizen other : settlement.members()) {
			if (other == citizen) {
				return ahead < available;
			}
			if (kind.usedBy(other)) {
				ahead++;
			}
		}
		return false;
	}
}
