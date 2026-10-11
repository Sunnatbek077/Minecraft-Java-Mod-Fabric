package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.RequestEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceManagerTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private static final class World {
		final HeadlessSimulation headless;
		final Simulation simulation;
		final Settlement settlement;
		final List<RequestEvent> requests = new ArrayList<>();
		final List<SettlementStageEvent> stages = new ArrayList<>();

		World(long seed, int members) {
			headless = new HeadlessSimulation(seed);
			simulation = headless.simulation();
			simulation.events().subscribe(RequestEvent.class, requests::add);
			simulation.events().subscribe(SettlementStageEvent.class, stages::add);
			for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members; v++) {
				simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
			}
			TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
			for (int i = 0; i < snapshot.columnCount(); i++) {
				snapshot.set(i, 64, Surface.SOIL);
			}
			settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		}

		/** Land with no room for any work site: nothing the settlers do can bring in wood. Food is no worry. */
		void barrenLandButPlentyOfFood() {
			settlement.setSurvey(settlement.siteScore(), Settlement.PlotMap.EMPTY);
			settlement.ledger().add(Commodity.FOOD, 100_000);
		}

		long count(RequestEvent.Kind kind) {
			return requests.stream().filter(r -> r.kind() == kind).count();
		}

		/** Runs until the settlement has an open request, or fails after a few days. */
		Request awaitRequest() {
			for (int i = 0; i < 400 && settlement.requests().isEmpty(); i++) {
				headless.runTicks(Labor.CYCLE_TICKS);
			}
			assertFalse(settlement.requests().isEmpty(), "a request was opened");
			return settlement.requests().getFirst();
		}

		int completed() {
			return (int) settlement.buildings().stream().filter(b -> b.stage() == Building.Stage.COMPLETE).count();
		}
	}

	@Test
	void foodStatusEntersAndLeavesAtDifferentMarks() {
		World world = new World(1, 4);
		Settlement settlement = world.settlement;
		Ledger ledger = settlement.ledger();
		ledger.take(Commodity.FOOD, ledger.onHand(Commodity.FOOD));

		ledger.add(Commodity.FOOD, 4 * 8);                // eight days
		world.headless.runTicks(Labor.CYCLE_TICKS);
		assertEquals(StockStatus.OK, settlement.status(Commodity.FOOD));

		ledger.take(Commodity.FOOD, ledger.onHand(Commodity.FOOD) - 4 * 4); // four days: low
		world.headless.runTicks(Labor.CYCLE_TICKS);
		assertEquals(StockStatus.LOW, settlement.status(Commodity.FOOD));

		ledger.add(Commodity.FOOD, 4 * 6 - ledger.onHand(Commodity.FOOD)); // six days: above the entry mark, below the exit
		world.headless.runTicks(Labor.CYCLE_TICKS);
		assertEquals(StockStatus.LOW, settlement.status(Commodity.FOOD), "does not flicker back at once");

		ledger.add(Commodity.FOOD, 4 * 9);
		world.headless.runTicks(Labor.CYCLE_TICKS);
		assertEquals(StockStatus.OK, settlement.status(Commodity.FOOD));
	}

	@Test
	void settlementAsksOnlyAfterTryingItselfAndNeverFloodsThePlayer() {
		World world = new World(2, 6);
		world.barrenLandButPlentyOfFood();

		world.headless.runTicks(ResourceManager.ASK_AFTER_TICKS / 2);
		assertEquals(StockStatus.LOW, world.settlement.status(Commodity.WOOD), "the hut cannot be paid for");
		assertEquals(0, world.count(RequestEvent.Kind.OPENED), "not at the first sign of a shortage");

		Request request = world.awaitRequest();
		assertEquals(Commodity.WOOD, request.commodity());
		assertEquals("hut", request.purpose());
		assertEquals(60, request.amount());

		world.headless.runDays(12);
		assertTrue(world.settlement.requests().size() <= ResourceManager.MAX_OPEN_REQUESTS);
		// Unanswered requests ran out and were asked again only after a pause, not every cycle.
		assertTrue(world.count(RequestEvent.Kind.EXPIRED) >= 2);
		assertTrue(world.count(RequestEvent.Kind.OPENED) <= 12, "opened " + world.count(RequestEvent.Kind.OPENED) + " times in 12 days");
	}

	@Test
	void partialGiftsCountAndTheLastOneClosesTheRequest() {
		World world = new World(3, 6);
		world.barrenLandButPlentyOfFood();
		Request request = world.awaitRequest();

		world.simulation.contribute(world.settlement, PLAYER, Commodity.WOOD, 25);
		assertEquals(25, world.settlement.requestFor(Commodity.WOOD).delivered());
		assertEquals(0, world.count(RequestEvent.Kind.FULFILLED));

		world.simulation.contribute(world.settlement, PLAYER, Commodity.WOOD, request.amount());
		assertNull(world.settlement.requestFor(Commodity.WOOD));
		world.headless.runTicks(1);
		assertEquals(1, world.count(RequestEvent.Kind.FULFILLED));
	}

	@Test
	void requestIsWithdrawnWhenTheSettlementManagesAlone() {
		World world = new World(4, 6);
		Ledger ledger = world.settlement.ledger();
		ledger.take(Commodity.FOOD, ledger.onHand(Commodity.FOOD)); // hungry now, but the fields will fix that
		Request request = world.awaitRequest();
		assertEquals(Commodity.FOOD, request.commodity());
		assertEquals(Request.FOOD_RESERVE, request.purpose());

		world.headless.runDays(6);
		assertNull(world.settlement.requestFor(Commodity.FOOD));
		assertTrue(world.count(RequestEvent.Kind.NO_LONGER_NEEDED) >= 1);
		assertTrue(world.settlement.size() >= 6, "nobody was lost");
	}

	@Test
	void whatIsAskedForEarnsStandingAndJunkDoesNot() {
		World world = new World(5, 6);
		world.barrenLandButPlentyOfFood();
		world.awaitRequest();

		world.simulation.contribute(world.settlement, PLAYER, Commodity.WOOD, 60);
		int forRequest = world.settlement.reputationOf(PLAYER);
		assertEquals(180, forRequest, "three points a unit for what was asked");

		world.simulation.contribute(world.settlement, PLAYER, Commodity.ORE, 10_000);
		int forJunk = world.settlement.reputationOf(PLAYER) - forRequest;
		assertEquals(1000, forJunk, "a tenth of a point a unit for what nobody needs");
		assertTrue(forJunk / 10_000.0 < forRequest / 60.0 / 20);
		assertEquals(10_060, world.settlement.contributionOf(PLAYER), "the gift itself is still recorded in full");
	}

	@Test
	void givingWhatIsAskedSpeedsTheSettlementUpButItManagesWithout() {
		World helped = new World(6, 6);
		World alone = new World(6, 6);

		for (int day = 0; day < 4; day++) {
			for (int cycle = 0; cycle < HeadlessSimulation.TICKS_PER_DAY / Labor.CYCLE_TICKS; cycle++) {
				helped.headless.runTicks(Labor.CYCLE_TICKS);
				alone.headless.runTicks(Labor.CYCLE_TICKS);
				for (Request request : List.copyOf(helped.settlement.requests())) {
					helped.simulation.contribute(helped.settlement, PLAYER, request.commodity(), request.remaining());
				}
			}
		}
		assertTrue(helped.completed() > alone.completed(), "with help " + helped.completed() + " buildings, without " + alone.completed());

		alone.headless.runDays(8);
		assertTrue(alone.completed() >= 3, "left alone it still builds: " + alone.completed());
		assertEquals(6, alone.settlement.members().stream().filter(c -> !c.isChild()).filter(c -> c.joinedTick() == 0).count());
	}

	@Test
	void plansAreTakenUpSideBySide() {
		World world = new World(7, 8);
		Settlement settlement = world.settlement;
		assertFalse(settlement.hasAnyPlan());
		assertTrue(world.simulation.adoptPlan(settlement, Policy.AGRARIAN));
		assertFalse(world.simulation.adoptPlan(settlement, Policy.AGRARIAN), "the same plan twice is refused");
		assertTrue(world.simulation.adoptPlan(settlement, Policy.INDUSTRY));
		assertTrue(world.simulation.adoptPlan(settlement, Policy.MILITARY));
		assertEquals(java.util.Set.of(Policy.AGRARIAN, Policy.INDUSTRY, Policy.MILITARY), settlement.plans());
		assertFalse(world.simulation.adoptPlan(settlement, Policy.NONE), "\"no plan\" is not a plan");
	}

	@Test
	void eachPlanStrengthensItsOwnSideAndTogetherTheyCostNothing() {
		World world = new World(8, 8);
		world.headless.runTicks(Labor.CYCLE_TICKS * 3);
		Settlement settlement = world.settlement;
		Citizen farmer = settlement.members().stream().filter(c -> c.job() == JobType.FARM).findFirst().orElseThrow();
		Citizen miner = settlement.members().stream().filter(c -> c.job() == JobType.MINE).findFirst().orElseThrow();
		int farming = Labor.efficiencyPercent(settlement, farmer, JobType.FARM);
		int mining = Labor.efficiencyPercent(settlement, miner, JobType.MINE);

		// One plan alone: its own side is stronger, and the price of specialising is paid.
		settlement.adoptPlan(Policy.MILITARY);
		assertEquals(80, settlement.birthPercent());
		assertEquals(farming, Labor.efficiencyPercent(settlement, farmer, JobType.FARM));

		settlement.adoptPlan(Policy.INDUSTRY);
		assertTrue(Labor.efficiencyPercent(settlement, miner, JobType.MINE) > mining);
		assertEquals(100, settlement.birthPercent(), "a second plan makes up for the first one's cost");
		assertEquals(4, settlement.growthRoom(), "and the first for the second's");

		// All three: stronger on every side, weaker on none.
		settlement.adoptPlan(Policy.AGRARIAN);
		assertTrue(Labor.efficiencyPercent(settlement, farmer, JobType.FARM) > farming);
		assertTrue(Labor.efficiencyPercent(settlement, miner, JobType.MINE) > mining);
		assertEquals(125, settlement.birthPercent());
		assertEquals(Policy.NONE.growthRoom(), settlement.growthRoom());
	}

	@Test
	void withoutAPlanTheCampLivesButDoesNotBecomeMore() {
		World world = new World(9, 6);
		world.headless.runDays(20);
		assertEquals(SettlementStage.FOUNDING_CAMP, world.settlement.stage());
		assertTrue(world.settlement.size() >= 6 && world.settlement.size() <= Population.softCap(SettlementStage.FOUNDING_CAMP));
		assertFalse(world.settlement.isStarving());
		assertEquals(List.of(), world.stages);

		world.simulation.adoptPlan(world.settlement, Policy.AGRARIAN);
		world.headless.runDays(12);
		assertTrue(world.settlement.stage().ordinal() >= SettlementStage.SETTLEMENT.ordinal(), "given a plan, it wakes up");
	}

	@Test
	void grownSettlementOpensMoreSitesInsteadOfLeavingHandsIdle() {
		World world = new World(10, 6);
		world.simulation.adoptPlan(world.settlement, Policy.AGRARIAN);
		world.headless.runDays(25);
		Settlement settlement = world.settlement;
		assertTrue(settlement.size() >= 14, "size " + settlement.size());
		assertTrue(settlement.sites().size() > 3, "sites " + settlement.sites().size());
		long idle = settlement.members().stream()
				.filter(c -> !c.isChild() && c.role() != Role.LEADER && c.role() != Role.GENERAL && c.job() == null).count();
		StringBuilder jobs = new StringBuilder();
		for (JobType job : JobType.values()) {
			jobs.append(job).append('=').append(settlement.members().stream().filter(c -> c.job() == job).count()).append(' ');
		}
		assertTrue(idle <= 2, "idle workers: " + idle + ", jobs " + jobs + ", sites " + settlement.sites().size()
				+ ", children " + settlement.children() + ", size " + settlement.size());
	}

	@Test
	void requestsStatusesPlanAndStandingSurviveSaveAndLoad(@TempDir Path dir) throws IOException {
		World world = new World(11, 6);
		world.barrenLandButPlentyOfFood();
		world.simulation.adoptPlan(world.settlement, Policy.MILITARY);
		world.awaitRequest();
		world.simulation.contribute(world.settlement, PLAYER, Commodity.WOOD, 20);

		SaveStore store = new SaveStore(dir);
		store.save(world.simulation.state());
		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(world.simulation.state()), store.export(loaded.state()));
		assertEquals(0, store.save(loaded.state()));

		Settlement restored = loaded.state().settlements().get(world.settlement.id()).orElseThrow();
		assertEquals(java.util.Set.of(Policy.MILITARY), restored.plans());
		assertNotNull(restored.requestFor(Commodity.WOOD));
		assertEquals(20, restored.requestFor(Commodity.WOOD).delivered());
		assertEquals(world.settlement.reputationOf(PLAYER), restored.reputationOf(PLAYER));
		assertEquals(StockStatus.LOW, restored.status(Commodity.WOOD));
	}
}
