package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenBornEvent;
import com.sunnatbek.villagerciv.api.SettlementStageEvent;
import com.sunnatbek.villagerciv.build.Building;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(808);
	private final Simulation simulation = headless.simulation();
	private final List<CitizenBornEvent> births = new ArrayList<>();
	private final List<SettlementStageEvent> promotions = new ArrayList<>();

	PopulationTest() {
		simulation.events().subscribe(CitizenBornEvent.class, births::add);
		simulation.events().subscribe(SettlementStageEvent.class, promotions::add);
	}

	private Settlement settle(int members) {
		for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members; v++) {
			simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		simulation.adoptPlan(settlement, Policy.AGRARIAN);
		return settlement;
	}

	@Test
	void sixFoundersGrowPastFifteenWithoutThePlayerAndNobodyStarves() {
		Settlement settlement = settle(6);
		List<Integer> sizes = new ArrayList<>();
		boolean starved = false;
		for (int day = 0; day < 20; day++) {
			headless.runDays(1);
			sizes.add(settlement.size());
			starved |= settlement.isStarving();
		}

		assertTrue(settlement.size() >= 15, "population by day: " + sizes);
		assertFalse(starved, "no hunger spiral");
		assertTrue(settlement.buildings().stream().filter(b -> b.stage() == Building.Stage.COMPLETE).count() >= 5);
		assertEquals(settlement.size() - 6, births.size());
		assertTrue(settlement.stage().ordinal() >= SettlementStage.SETTLEMENT.ordinal(), "the camp became a settlement on the way");
		assertEquals(settlement.stage().ordinal(), promotions.size(), "one promotion per stage climbed");
		assertTrue(settlement.size() <= Population.softCap(settlement.stage()));
		assertTrue(settlement.morale() >= 50, "morale " + settlement.morale());
		// Growth never ran ahead of housing: everyone has a bed.
		assertTrue(settlement.members().stream().allMatch(c -> c.home() != Citizen.NO_HOME));
		assertTrue(settlement.size() <= settlement.housingCapacity());
	}

	@Test
	void childrenDoNotWorkEatHalfAndGrowUpIntoTheJobBoard() {
		Settlement settlement = settle(6);
		// A child, not a grown newcomer walking in: both are announced the same way.
		while (births.stream().allMatch(CitizenBornEvent::newcomer)) {
			headless.runTicks(Labor.CYCLE_TICKS);
			assertTrue(headless.gameTick() < 30 * HeadlessSimulation.TICKS_PER_DAY, "a child is born eventually");
		}
		Citizen child = settlement.member(births.stream().filter(e -> !e.newcomer()).findFirst().orElseThrow().citizen()).orElseThrow();
		assertTrue(child.isChild());
		assertTrue(settlement.children() >= 1);

		headless.runTicks(Labor.CYCLE_TICKS * 3);
		assertNull(child.job(), "children do not work");
		assertTrue(child.role() == Role.CHILD, "and are never made Leader or General");

		headless.runTicks(Population.CHILDHOOD_TICKS + Labor.CYCLE_TICKS * 14);
		assertFalse(child.isChild(), "grown up after its childhood");
		assertTrue(child.job() != null || child.role() == Role.LEADER || child.role() == Role.GENERAL, "and at work");
	}

	@Test
	void noBirthsWithoutSpareBedsFoodOrMorale() {
		Settlement settlement = settle(6);
		// No houses at all: the plot map is taken away, so nothing can be built.
		settlement.setSurvey(settlement.siteScore(), Settlement.PlotMap.EMPTY);
		settlement.ledger().add(Commodity.FOOD, 5000);
		headless.runDays(10);
		assertEquals(List.of(), births, "nowhere to put a child");
		assertEquals(6, settlement.size());
		assertEquals(SettlementStage.FOUNDING_CAMP, settlement.stage());
	}

	@Test
	void aParentWaitsBeforeAnotherChild() {
		Settlement settlement = settle(6);
		headless.runDays(12);
		for (Citizen citizen : settlement.members()) {
			if (citizen.lastChildTick() > 0) {
				assertTrue(headless.gameTick() >= citizen.lastChildTick());
			}
		}
		// With six adults sharing the cooldown, no more than three couples per cooldown period.
		long days = 12;
		long periods = days * HeadlessSimulation.TICKS_PER_DAY / Population.BIRTH_COOLDOWN_TICKS + 1;
		assertTrue(births.size() <= periods * settlement.size() / 2, "births: " + births.size());
	}

	@Test
	void foodDaysAndMoraleReflectTheSettlement() {
		Settlement settlement = settle(4);
		settlement.ledger().take(Commodity.FOOD, settlement.ledger().onHand(Commodity.FOOD));
		settlement.ledger().add(Commodity.FOOD, 20);
		assertEquals(5, settlement.foodDays());

		int fed = Population.morale(settlement);
		settlement.setStarving(true);
		assertTrue(Population.morale(settlement) < fed, "hunger weighs on morale");
	}

	@Test
	void populationStateSurvivesSaveAndLoad(@TempDir Path dir) throws IOException {
		Settlement settlement = settle(6);
		headless.runDays(8);
		assertFalse(births.isEmpty());

		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());
		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(simulation.state()), store.export(loaded.state()));
		Settlement restored = loaded.state().settlements().get(settlement.id()).orElseThrow();
		assertEquals(settlement.children(), restored.children());
		assertEquals(settlement.morale(), restored.morale());
		assertEquals(settlement.stage(), restored.stage());
	}
}
