package com.sunnatbek.villagerciv.sim;

/**
 * What a market is for. A settlement with a merchant at work no longer has to make everything it
 * needs with its own hands: whatever it is short of is bought with whatever it has plenty of.
 * The far side of the bargain is not played out; the merchant deals with pedlars and passing
 * carters, and the rate is a poor one, as it is for anyone who has to buy.
 */
final class Trade {
	/** Goods given, and goods got for them, in one deal. */
	static final int GIVEN = 12;
	static final int GOT = 8;
	/** A commodity is only sold from when there is this much of it to spare. */
	static final int SURPLUS = 400;
	private static final int SURPLUS_FOOD_DAYS = 10;
	private static final Commodity[] BOUGHT = {Commodity.FOOD, Commodity.WOOD, Commodity.STONE};
	private static final Commodity[] SOLD = {Commodity.ORE, Commodity.STONE, Commodity.WOOD, Commodity.FOOD};

	private Trade() {
	}

	/** One round of dealing: at most one deal for each thing that is short. */
	static void update(Settlement settlement) {
		if (!settlement.hasWorker(JobType.TRADE)) {
			return;
		}
		Ledger ledger = settlement.ledger();
		for (Commodity wanted : BOUGHT) {
			if (settlement.status(wanted) == StockStatus.OK) {
				continue;
			}
			// Sold from whatever there is most of to spare; never from something that is short itself.
			Commodity best = null;
			for (Commodity offered : SOLD) {
				boolean spare = offered != wanted && settlement.status(offered) == StockStatus.OK && ledger.available(offered) >= SURPLUS
						&& (offered != Commodity.FOOD || settlement.foodDays() >= SURPLUS_FOOD_DAYS);
				if (spare && (best == null || ledger.available(offered) > ledger.available(best))) {
					best = offered;
				}
			}
			if (best != null) {
				ledger.take(best, GIVEN);
				ledger.add(wanted, GOT);
			}
		}
	}
}
