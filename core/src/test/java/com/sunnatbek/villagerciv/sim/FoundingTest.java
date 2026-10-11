package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.SettlementFoundedEvent;
import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoundingTest {
	private static final UUID PLAYER = new UUID(1, 1);
	private static final UUID OTHER_PLAYER = new UUID(1, 2);

	private final HeadlessSimulation headless = new HeadlessSimulation(404);
	private final Simulation simulation = headless.simulation();
	private long villagers;

	private void recruit(UUID player, int members) {
		while (simulation.expeditionOf(player).map(Expedition::size).orElse(0) < members) {
			simulation.recruit(player, new UUID(2, villagers++), "farmer", null, false, 0, 64, 0);
		}
	}

	/** Level grassland at height 64 with a river to the east and a forest to the north. */
	private static TerrainSnapshot meadow(int centerX, int centerZ) {
		TerrainSnapshot snapshot = new TerrainSnapshot(centerX, centerZ, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			int dx = snapshot.blockX(i) - centerX;
			int dz = snapshot.blockZ(i) - centerZ;
			Surface surface = Surface.SOIL;
			if (dx >= 20 && dx <= 24) {
				surface = Surface.WATER;
			} else if (dz < -25) {
				surface = Surface.TREE;
			} else if (dx < -35) {
				surface = Surface.STONE;
			}
			snapshot.set(i, 64, surface);
		}
		return snapshot;
	}

	private static TerrainSnapshot uniform(Surface surface, int heightStep) {
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64 + Math.floorMod(snapshot.blockX(i), 2) * heightStep, surface);
		}
		return snapshot;
	}

	@Test
	void goodLandScoresWellAndEachAspectReflectsTheTerrain() {
		SiteScore score = SiteSurvey.analyse(meadow(0, 0)).score();
		assertTrue(score.canSettle());
		assertTrue(score.total() >= 70, "total " + score.total());
		assertTrue(score.water() >= 70, "water " + score.water());
		assertTrue(score.wood() >= 90 && score.stone() >= 50 && score.fertility() >= 90);
		assertTrue(score.flatness() >= 90, "the river takes a little level ground: " + score.flatness());

		SiteScore desert = SiteSurvey.analyse(uniform(Surface.SAND, 0)).score();
		assertTrue(desert.canSettle());
		assertEquals(0, desert.water());
		assertEquals(0, desert.wood());
		assertTrue(desert.total() < score.total());
	}

	@Test
	void impossiblePlacesAreRefusedOutright() {
		assertEquals(SiteScore.Problem.NOT_ON_LAND, SiteSurvey.analyse(uniform(Surface.WATER, 0)).score().problem());
		assertEquals(SiteScore.Problem.NO_ROOM, SiteSurvey.analyse(uniform(Surface.STONE, 6)).score().problem());
		assertEquals(SiteScore.Problem.NOT_SURVEYED, SiteSurvey.analyse(new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS)).score().problem());
	}

	@Test
	void plotMapMarksLevelLandOnly() {
		Settlement.PlotMap plots = SiteSurvey.analyse(meadow(100, -200)).plots();
		assertTrue(plots.isBuildable(100, -200));
		assertFalse(plots.isBuildable(122, -200), "river");
		assertFalse(plots.isBuildable(100, -230), "forest");
		assertFalse(plots.isBuildable(5000, 5000), "outside the survey");
		assertEquals(0, SiteSurvey.analyse(uniform(Surface.STONE, 6)).plots().buildableCount());
	}

	@Test
	void expeditionBecomesASettlementWithItsPeopleAndSupplies() {
		recruit(PLAYER, 6);
		Expedition expedition = simulation.expeditionOf(PLAYER).orElseThrow();
		List<Citizen> founders = new ArrayList<>(expedition.members());
		int food = expedition.food();
		List<SettlementFoundedEvent> events = new ArrayList<>();
		simulation.events().subscribe(SettlementFoundedEvent.class, events::add);

		assertEquals(Founding.Refusal.NONE, simulation.canFound(PLAYER, 100, -200));
		Settlement settlement = simulation.found(PLAYER, 100, 64, -200, SiteSurvey.analyse(meadow(100, -200))).orElseThrow();

		assertTrue(simulation.expeditionOf(PLAYER).isEmpty(), "the expedition is gone");
		assertEquals(founders, new ArrayList<>(settlement.members()), "same citizen records, not copies");
		assertEquals(food, settlement.ledger().onHand(Commodity.FOOD));
		assertEquals(SettlementStage.FOUNDING_CAMP, settlement.stage());
		assertEquals(PLAYER, settlement.founder());
		assertTrue(settlement.siteScore().total() > 0 && settlement.plots().buildableCount() > 0);
		assertEquals(1, settlement.members().stream().filter(c -> c.role() == Role.LEADER).count());

		// The Town Center's own chunk is claimed, and land well outside the radius is not.
		assertEquals(settlement, simulation.state().territoryOwner(ChunkKey.ofBlock(100, -200)).orElseThrow());
		assertTrue(simulation.state().territoryOwner(ChunkKey.ofBlock(100 + 200, -200)).isEmpty());
		assertTrue(settlement.territory().size() >= 16 && settlement.territory().size() <= 30, "chunks: " + settlement.territory().size());

		// A change to a member now marks the settlement for saving.
		settlement.markClean();
		founders.getFirst().setHealth(3f);
		assertTrue(settlement.isDirty());

		headless.runTicks(1);
		assertEquals(1, events.size());
	}

	@Test
	void foundingHasPreconditions() {
		assertEquals(Founding.Refusal.NO_EXPEDITION, simulation.canFound(PLAYER, 0, 0));
		recruit(PLAYER, Founding.MIN_FOUNDERS - 1);
		assertEquals(Founding.Refusal.TOO_FEW_FOUNDERS, simulation.canFound(PLAYER, 0, 0));
		recruit(PLAYER, Founding.MIN_FOUNDERS);
		simulation.expeditionOf(PLAYER).orElseThrow().setState(Expedition.State.CAMPED);
		assertEquals(Founding.Refusal.NOT_TRAVELLING, simulation.canFound(PLAYER, 0, 0));
		simulation.expeditionOf(PLAYER).orElseThrow().setState(Expedition.State.FOLLOWING);

		assertTrue(simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(uniform(Surface.WATER, 0))).isEmpty(), "bad site");
		assertTrue(simulation.expeditionOf(PLAYER).isPresent());
		assertTrue(simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(meadow(0, 0))).isPresent());
	}

	@Test
	void territoriesDoNotOverlap() {
		recruit(PLAYER, 4);
		simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(meadow(0, 0))).orElseThrow();

		recruit(OTHER_PLAYER, 4);
		assertEquals(Founding.Refusal.TERRITORY_TAKEN, simulation.canFound(OTHER_PLAYER, 60, 0));
		assertTrue(simulation.found(OTHER_PLAYER, 60, 64, 0, SiteSurvey.analyse(meadow(60, 0))).isEmpty());
		assertEquals(Founding.Refusal.NONE, simulation.canFound(OTHER_PLAYER, 200, 0));
	}

	@Test
	void deadSettlerIsRemovedAndRolesRefill() {
		recruit(PLAYER, 5);
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(meadow(0, 0))).orElseThrow();
		Citizen leader = settlement.members().stream().filter(c -> c.role() == Role.LEADER).findFirst().orElseThrow();

		simulation.citizenDied(settlement, leader.id());
		assertEquals(4, settlement.size());
		assertEquals(1, settlement.members().stream().filter(c -> c.role() == Role.LEADER).count());
	}

	@Test
	void settlementSurvivesSaveAndLoadExactly(@TempDir Path dir) throws IOException {
		recruit(PLAYER, 5);
		Settlement settlement = simulation.found(PLAYER, 100, 70, -200, SiteSurvey.analyse(meadow(100, -200))).orElseThrow();
		settlement.ledger().add(Commodity.STONE, 40);
		settlement.ledger().reserve(Commodity.STONE, 15);
		settlement.contribute(PLAYER, Commodity.WOOD, 12);
		settlement.contribute(OTHER_PLAYER, Commodity.WOOD, 3);
		settlement.contribute(PLAYER, Commodity.FOOD, 5);

		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());
		SaveStore.LoadResult loaded = store.load(0);

		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(simulation.state()), store.export(loaded.state()));
		assertEquals(0, store.save(loaded.state()), "nothing is dirty right after loading");

		Settlement restored = loaded.state().settlements().get(settlement.id()).orElseThrow();
		assertEquals(25, restored.ledger().available(Commodity.STONE));
		assertEquals(15, restored.ledger().onHand(Commodity.WOOD));
		assertEquals(17, restored.contributionOf(PLAYER));
		assertEquals(3, restored.contributionOf(OTHER_PLAYER));
		assertEquals(0, restored.contributionOf(new UUID(7, 7)));
		assertEquals(settlement.plots().buildable(), restored.plots().buildable());
		assertEquals(restored, loaded.state().territoryOwner(ChunkKey.ofBlock(100, -200)).orElseThrow());
		assertEquals(0, loaded.state().expeditions().size());
	}

	@Test
	void aVillagerAskedOnSettlementLandJoinsItDirectly() {
		recruit(PLAYER, Founding.MIN_FOUNDERS);
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(meadow(0, 0))).orElseThrow();
		int before = settlement.size();

		Citizen joined = null;
		for (int attempt = 0; attempt < 200 && joined == null; attempt++) {
			if (simulation.recruit(PLAYER, new UUID(2, villagers++), "none", null, false, 5, 64, -3) instanceof Recruitment.Accepted accepted) {
				assertEquals(settlement, accepted.group());
				joined = accepted.citizen();
			}
		}
		assertEquals(before + 1, settlement.size());
		assertTrue(simulation.expeditionOf(PLAYER).isEmpty(), "no expedition is started for them");

		// Somebody else asking on the same land only gathers an expedition of their own.
		while (simulation.expeditionOf(OTHER_PLAYER).isEmpty()) {
			simulation.recruit(OTHER_PLAYER, new UUID(2, villagers++), "none", null, false, 5, 64, -3);
		}
		assertEquals(before + 1, settlement.size());
	}

	@Test
	void followersMoveIntoASettlementOfTheirPatron() {
		recruit(PLAYER, Founding.MIN_FOUNDERS);
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(meadow(0, 0))).orElseThrow();
		int before = settlement.size();
		int food = settlement.ledger().onHand(Commodity.FOOD);

		// Recruited far from home, so they follow the player.
		while (simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < 3) {
			simulation.recruit(PLAYER, new UUID(2, villagers++), "farmer", null, false, 5_000, 64, 5_000);
		}
		assertTrue(simulation.settleIn(OTHER_PLAYER, settlement).isEmpty(), "only into one's own settlement");
		assertEquals(3, simulation.settleIn(PLAYER, settlement).size());
		assertEquals(before + 3, settlement.size());
		assertTrue(simulation.expeditionOf(PLAYER).isEmpty());
		assertTrue(settlement.ledger().onHand(Commodity.FOOD) > food, "their provisions come with them");
	}
}
