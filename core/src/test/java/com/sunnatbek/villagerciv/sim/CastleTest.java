package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastleTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(707);
	private final Simulation simulation = headless.simulation();

	private Settlement settle() {
		for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < 8; v++) {
			simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		return simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
	}

	@Test
	void aCastleIsBuiltInSevenDaysByItsBrigadeAndPaidForInStone() {
		Settlement settlement = settle();
		assertFalse(Castle.mayBegin(settlement), "a camp has no great wall to build it on");
		settlement.ledger().add(Commodity.FOOD, 50_000);
		settlement.ledger().add(Commodity.STONE, 1_000_000);
		Castle.begin(settlement, 120, 2_800_000);
		assertTrue(Castle.isBuilding(settlement));

		headless.runTicks(Labor.CYCLE_TICKS * 3);
		long brigade = settlement.members().stream().filter(c -> c.job() == JobType.BUILD && c.jobSite() == Labor.CASTLE).count();
		assertTrue(brigade >= 1, "hands are put to it: " + brigade);

		int stone = settlement.ledger().onHand(Commodity.STONE);
		headless.runDays(3);
		long built = Castle.built(settlement);
		assertTrue(built > 2_800_000L * 3 / 7 && built < 2_800_000L * 4 / 7, "about three sevenths after three days: " + built);
		int used = stone - settlement.ledger().onHand(Commodity.STONE);
		assertTrue(used >= 2_800_000 * 3 / 7 / Castle.BLOCKS_PER_STONE - 2_000, "stone goes into it: " + used);

		headless.runDays(5);
		assertEquals(2_800_000L, Castle.built(settlement), "and it is all up within the seven days");
		assertTrue(Castle.isFinished(settlement));
		headless.runTicks(Labor.CYCLE_TICKS * 3);
		assertEquals(0, settlement.members().stream().filter(c -> c.jobSite() == Labor.CASTLE && c.job() == JobType.BUILD).count(),
				"the brigade goes back to other work");
	}

	@Test
	void anEmptyStoreDoesNotHoldTheCastleBack() {
		Settlement settlement = settle();
		settlement.ledger().add(Commodity.FOOD, 50_000);
		Castle.begin(settlement, 120, 700_000);
		headless.runDays(8);
		assertTrue(Castle.isFinished(settlement), "the day was promised");
	}

	@Test
	void theCastleIsSaved(@TempDir Path dir) throws IOException {
		Settlement settlement = settle();
		Castle.begin(settlement, 131, 2_715_415);
		headless.runTicks(Labor.CYCLE_TICKS * 10);
		long built = Castle.built(settlement);
		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());
		Settlement loaded = store.load(0).state().settlements().get(settlement.id()).orElseThrow();
		assertEquals(131, loaded.castleY());
		assertEquals(2_715_415, loaded.castleBlocks());
		assertEquals(built, Castle.built(loaded));
	}
}
