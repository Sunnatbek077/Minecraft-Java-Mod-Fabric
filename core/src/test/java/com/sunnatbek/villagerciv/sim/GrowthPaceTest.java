package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenBornEvent;
import com.sunnatbek.villagerciv.api.SettlementStageEvent;
import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Long runs that watch the whole growth curve rather than one rule. */
class GrowthPaceTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private static Settlement settle(HeadlessSimulation headless, Policy policy) {
		Simulation simulation = headless.simulation();
		for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < 8; v++) {
			simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		if (policy != Policy.NONE) {
			simulation.adoptPlan(settlement, policy);
		}
		return settlement;
	}

	@Test
	void aCampWithSpareBedsFillsUpInDaysNotWeeks() {
		HeadlessSimulation headless = new HeadlessSimulation(21);
		Settlement settlement = settle(headless, Policy.NONE);
		headless.runDays(8);
		// No plan, so the camp stops at its cap, but it gets there briskly.
		assertEquals(Population.softCap(SettlementStage.FOUNDING_CAMP), settlement.size());
		assertEquals(SettlementStage.FOUNDING_CAMP, settlement.stage());
	}

	@Test
	void withAPlanTheSettlementClimbsToAVillage() {
		HeadlessSimulation headless = new HeadlessSimulation(22);
		List<SettlementStageEvent> promotions = new ArrayList<>();
		headless.simulation().events().subscribe(SettlementStageEvent.class, promotions::add);
		Settlement settlement = settle(headless, Policy.AGRARIAN);

		List<Integer> sizes = new ArrayList<>();
		boolean starved = false;
		for (int day = 0; day < 45; day++) {
			headless.runDays(1);
			sizes.add(settlement.size());
			starved |= settlement.isStarving();
		}
		assertEquals(SettlementStage.VILLAGE, settlement.stage(), "population by day: " + sizes);
		assertEquals(2, promotions.size());
		assertTrue(settlement.size() > Population.softCap(SettlementStage.SETTLEMENT), "and keeps growing as a village: " + sizes);
		assertFalse(starved, "without ever going hungry");
		assertTrue(settlement.members().stream().allMatch(c -> c.home() != Citizen.NO_HOME), "and everyone housed");
	}

	@Test
	void fullStoresTurnIntoPeople() {
		HeadlessSimulation headless = new HeadlessSimulation(23);
		List<CitizenBornEvent> arrivals = new ArrayList<>();
		headless.simulation().events().subscribe(CitizenBornEvent.class, event -> {
			if (event.newcomer()) {
				arrivals.add(event);
			}
		});
		Settlement settlement = settle(headless, Policy.INDUSTRY);
		settlement.ledger().add(Commodity.FOOD, 600);
		settlement.ledger().add(Commodity.WOOD, 1_000);
		settlement.ledger().add(Commodity.STONE, 800);

		headless.runDays(4);
		assertFalse(arrivals.isEmpty(), "a well-stocked settlement draws newcomers");
		assertTrue(settlement.stage().ordinal() >= SettlementStage.SETTLEMENT.ordinal(), "and is promoted within days");
		assertTrue(settlement.size() >= 12, "size after four days: " + settlement.size());
		assertTrue(settlement.buildings().stream().filter(b -> b.stage() == com.sunnatbek.villagerciv.build.Building.Stage.COMPLETE).count() >= 3,
				"and builds with what it was given");
	}

	@Test
	void aVillageWithItsTradesBecomesATown() {
		HeadlessSimulation headless = new HeadlessSimulation(24);
		List<SettlementStageEvent> promotions = new ArrayList<>();
		headless.simulation().events().subscribe(SettlementStageEvent.class, promotions::add);
		Settlement settlement = settle(headless, Policy.AGRARIAN);
		headless.simulation().adoptPlan(settlement, Policy.INDUSTRY);
		settlement.ledger().add(Commodity.FOOD, 100_000);
		settlement.ledger().add(Commodity.WOOD, 100_000);
		settlement.ledger().add(Commodity.STONE, 100_000);
		settlement.ledger().add(Commodity.ORE, 5_000);

		List<Integer> sizes = new ArrayList<>();
		for (int day = 0; day < 60 && settlement.stage() != SettlementStage.TOWN; day++) {
			headless.runDays(1);
			sizes.add(settlement.size());
		}
		assertEquals(SettlementStage.TOWN, settlement.stage(), "population by day: " + sizes);
		assertEquals(3, promotions.size());
		assertTrue(settlement.workshop(com.sunnatbek.villagerciv.build.Blueprint.SMITHY) != null
				&& settlement.workshop(com.sunnatbek.villagerciv.build.Blueprint.MASONS_YARD) != null, "it has its workshops");
		assertTrue(settlement.hasWorker(JobType.SMITH) && settlement.hasWorker(JobType.MASON), "and people at work in them");
		assertTrue(settlement.size() >= Population.promotionSize(SettlementStage.VILLAGE));
		Citizen smith = settlement.members().stream().filter(c -> c.job() == JobType.SMITH).findFirst().orElseThrow();
		assertEquals(com.sunnatbek.villagerciv.build.Blueprint.SMITHY, settlement.building(smith.jobSite()).blueprint(), "the smith works in the smithy");
		assertEquals(Tools.FORGED_SPEED_PERCENT, Tools.speedPercent(settlement), "forged tools are better tools");
	}

	@Test
	void aMerchantBuysWhatIsShortWithWhatIsPlentyAndASmallTownLosesItsName() {
		HeadlessSimulation headless = new HeadlessSimulation(25);
		List<SettlementStageEvent> changes = new ArrayList<>();
		headless.simulation().events().subscribe(SettlementStageEvent.class, changes::add);
		Settlement settlement = settle(headless, Policy.AGRARIAN);
		headless.simulation().adoptPlan(settlement, Policy.INDUSTRY);
		settlement.ledger().add(Commodity.FOOD, 100_000);
		settlement.ledger().add(Commodity.WOOD, 100_000);
		settlement.ledger().add(Commodity.STONE, 100_000);
		settlement.ledger().add(Commodity.ORE, 5_000);
		for (int day = 0; day < 60 && !settlement.hasWorker(JobType.TRADE); day++) {
			headless.runDays(1);
		}
		assertTrue(settlement.hasWorker(JobType.TRADE), "a village builds a market and someone keeps it");

		// Wood runs out with a building waiting for it; ore there is plenty of.
		settlement.ledger().take(Commodity.WOOD, settlement.ledger().available(Commodity.WOOD));
		int ore = settlement.ledger().onHand(Commodity.ORE);
		headless.runDays(2);
		assertTrue(settlement.ledger().onHand(Commodity.ORE) < ore || settlement.status(Commodity.WOOD) == StockStatus.OK,
				"ore is sold for wood while wood is short");

		// Most of its people gone, it is no longer what it was.
		SettlementStage before = settlement.stage();
		List<Citizen> leaving = new ArrayList<>(settlement.members()).subList(4, settlement.size());
		for (Citizen citizen : List.copyOf(leaving)) {
			headless.simulation().citizenDied(settlement, citizen.id());
		}
		// And too poor to draw anyone back: with full stores newcomers would refill it within the day.
		for (Commodity commodity : List.of(Commodity.WOOD, Commodity.STONE, Commodity.ORE)) {
			if (settlement.ledger().available(commodity) > 0) {
				settlement.ledger().take(commodity, settlement.ledger().available(commodity)); // nothing to sell for food either
			}
		}
		settlement.ledger().take(Commodity.FOOD, settlement.ledger().available(Commodity.FOOD) - 8);
		headless.runTicks(Population.DECLINE_HOLD_TICKS + 2 * ExpeditionUpkeep.STEP_TICKS + Labor.CYCLE_TICKS);
		assertTrue(settlement.stage().ordinal() < before.ordinal(), "it fell from " + before + " and is " + settlement.stage());
		assertTrue(changes.getLast().declined());
	}
}
