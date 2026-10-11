package com.sunnatbek.villagerciv.sim;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {
	private int changes;
	private final Ledger ledger = new Ledger(() -> changes++);

	@Test
	void reservedStockCannotBeSpentTwice() {
		ledger.add(Commodity.STONE, 100);
		assertTrue(ledger.reserve(Commodity.STONE, 70));
		assertEquals(30, ledger.available(Commodity.STONE));

		assertFalse(ledger.reserve(Commodity.STONE, 40), "a second project cannot have the same stone");
		assertFalse(ledger.take(Commodity.STONE, 40));
		assertTrue(ledger.take(Commodity.STONE, 30));
		assertEquals(70, ledger.onHand(Commodity.STONE));

		assertTrue(ledger.consumeReserved(Commodity.STONE, 70));
		assertEquals(0, ledger.onHand(Commodity.STONE));
		assertEquals(0, ledger.reserved(Commodity.STONE));
	}

	@Test
	void releasingGivesStockBackAndNeverGoesNegative() {
		ledger.add(Commodity.WOOD, 10);
		ledger.reserve(Commodity.WOOD, 10);
		ledger.release(Commodity.WOOD, 4);
		assertEquals(4, ledger.available(Commodity.WOOD));
		ledger.release(Commodity.WOOD, 100);
		assertEquals(0, ledger.reserved(Commodity.WOOD));
		assertFalse(ledger.consumeReserved(Commodity.WOOD, 1));
	}

	@Test
	void changesAreReportedAndBadAmountsRejected() {
		ledger.add(Commodity.FOOD, 5);
		assertEquals(1, changes);
		assertFalse(ledger.take(Commodity.FOOD, 6));
		assertEquals(1, changes, "a failed operation changes nothing");
		assertThrows(IllegalArgumentException.class, () -> ledger.add(Commodity.FOOD, 0));
		assertThrows(IllegalArgumentException.class, () -> ledger.take(Commodity.FOOD, -3));
	}

	@Test
	void invariantHoldsUnderRandomOperations() {
		SplittableRandom random = new SplittableRandom(11);
		Commodity[] commodities = Commodity.values();
		for (int i = 0; i < 20_000; i++) {
			Commodity commodity = commodities[random.nextInt(commodities.length)];
			int amount = 1 + random.nextInt(50);
			switch (random.nextInt(5)) {
				case 0 -> ledger.add(commodity, amount);
				case 1 -> ledger.take(commodity, amount);
				case 2 -> ledger.reserve(commodity, amount);
				case 3 -> ledger.release(commodity, amount);
				default -> ledger.consumeReserved(commodity, amount);
			}
			assertTrue(ledger.reserved(commodity) >= 0 && ledger.reserved(commodity) <= ledger.onHand(commodity),
					() -> "step broke the invariant for " + commodity);
		}
	}
}
