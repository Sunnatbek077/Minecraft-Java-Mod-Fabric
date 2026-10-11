package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenLeftEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaborTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(303);
	private final Simulation simulation = headless.simulation();
	private long villagers;

	private Settlement settle(int members, Surface ground) {
		while (simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members) {
			simulation.recruit(PLAYER, new UUID(2, villagers++), villagers % 3 == 0 ? "farmer" : "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, ground);
		}
		return simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
	}

	private static long count(Settlement settlement, JobType job) {
		return settlement.members().stream().filter(c -> c.job() == job).count();
	}

	@Test
	void everyJobGetsItsOwnSiteClearOfTheOthers() {
		Settlement settlement = settle(6, Surface.SOIL);
		headless.runTicks(Labor.CYCLE_TICKS * 2);

		assertEquals(3, settlement.sites().size());
		for (WorkSite a : settlement.sites()) {
			double distance = Math.hypot(a.x() - settlement.anchorX(), a.z() - settlement.anchorZ());
			assertTrue(distance >= 11 && distance <= 41, a.type() + " at distance " + distance);
			assertTrue(Math.abs(a.directionX()) + Math.abs(a.directionZ()) == 1);
			for (WorkSite b : settlement.sites()) {
				assertTrue(a == b || Math.abs(a.x() - b.x()) >= 9 || Math.abs(a.z() - b.z()) >= 9, "sites overlap");
			}
		}

		// No more sites appear once every job has one.
		headless.runTicks(Labor.CYCLE_TICKS * 5);
		assertEquals(3, settlement.sites().size());
	}

	@Test
	void landWithoutRoomGetsNoSitesAndNobodyWorks() {
		Settlement settlement = settle(4, Surface.SOIL);
		settlement.setSurvey(settlement.siteScore(), Settlement.PlotMap.EMPTY);
		headless.runTicks(Labor.CYCLE_TICKS * 3);

		assertEquals(0, settlement.sites().size());
		assertTrue(settlement.members().stream().allMatch(c -> c.job() == null));
	}

	@Test
	void workersSpreadOverTheJobsWithinTheSlotsEachSiteOffers() {
		Settlement settlement = settle(8, Surface.SOIL);
		headless.runTicks(Labor.CYCLE_TICKS * 3);

		// Leader and General do not take jobs; the other six do.
		assertEquals(6, settlement.members().stream().filter(c -> c.job() != null).count());
		assertTrue(settlement.members().stream().filter(c -> c.role() == Role.LEADER || c.role() == Role.GENERAL)
				.allMatch(c -> c.job() == null));
		for (JobType job : List.of(JobType.FARM, JobType.LUMBER, JobType.MINE)) {
			assertTrue(count(settlement, job) >= 1 && count(settlement, job) <= job.slotsPerSite(), job + ": " + count(settlement, job));
		}
	}

	@Test
	void farmersGoToThoseSuitedAndFoodShortageCallsForMore() {
		// Six members: four workers, fewer than the sites could take, so the quota decides.
		Settlement settlement = settle(6, Surface.SOIL);
		settlement.ledger().add(Commodity.FOOD, 1000);
		headless.runTicks(Labor.CYCLE_TICKS * 3);
		long farmersWhenFed = count(settlement, JobType.FARM);
		assertTrue(settlement.members().stream().filter(c -> c.job() == JobType.FARM).allMatch(c -> c.affinity() == Role.FARMER),
				"those who know farming get the farm jobs first");

		Settlement hungry = settlementWithFood(0);
		assertTrue(count(hungry, JobType.FARM) > farmersWhenFed, "more farmers when food is short");
	}

	private Settlement settlementWithFood(int food) {
		HeadlessSimulation other = new HeadlessSimulation(303);
		for (long v = 0; other.simulation().expeditionOf(PLAYER).map(Expedition::size).orElse(0) < 6; v++) {
			other.simulation().recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		Settlement settlement = other.simulation().found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		settlement.ledger().take(Commodity.FOOD, settlement.ledger().onHand(Commodity.FOOD) - food);
		other.runTicks(Labor.CYCLE_TICKS * 3);
		return settlement;
	}

	@Test
	void jobOfAWorkerWhoShowsNoProgressGoesBackToTheBoard() {
		Settlement settlement = settle(8, Surface.SOIL);
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		Citizen miner = settlement.members().stream().filter(c -> c.job() == JobType.MINE).findFirst().orElseThrow();

		// A body that is stuck: it holds the job but never reports or delivers.
		miner.setEmbodied(true);
		headless.runTicks(Labor.LEASE_TICKS + Labor.CYCLE_TICKS * 2);
		assertTrue(miner.leaseUntil() > headless.gameTick() - Labor.LEASE_TICKS - Labor.CYCLE_TICKS,
				"the lapsed job was handed out again, with a fresh lease");
		assertEquals(1, count(settlement, JobType.MINE), "the single mine slot is never double-booked");

		Labor.reportProgress(miner, headless.gameTick());
		assertEquals(headless.gameTick() + Labor.LEASE_TICKS, miner.leaseUntil());
	}

	@Test
	void deliveredLoadEntersTheLedgerOnce() {
		Settlement settlement = settle(4, Surface.SOIL);
		Citizen worker = settlement.members().iterator().next();
		int before = settlement.ledger().onHand(Commodity.WOOD);

		worker.carry(Commodity.WOOD, 12);
		worker.carry(Commodity.WOOD, 4);
		Labor.deliver(settlement, worker, 0);
		Labor.deliver(settlement, worker, 0);
		assertEquals(before + 16, settlement.ledger().onHand(Commodity.WOOD));
		assertNull(worker.carrying());
	}

	@Test
	void settlementLivesThreeDaysWithoutThePlayerAndItsStocksGrow() {
		Settlement settlement = settle(6, Surface.SOIL);
		int food = settlement.ledger().onHand(Commodity.FOOD);
		List<Integer> foodByDay = new ArrayList<>();

		for (int day = 0; day < 3; day++) {
			headless.runDays(1);
			foodByDay.add(settlement.ledger().onHand(Commodity.FOOD));
		}

		assertEquals(6, settlement.size(), "nobody left");
		assertTrue(!settlement.isStarving());
		assertTrue(foodByDay.get(2) > food, "food grew despite six mouths: " + food + " -> " + foodByDay);
		assertTrue(settlement.ledger().onHand(Commodity.WOOD) > 0, "wood");
		assertTrue(settlement.ledger().onHand(Commodity.STONE) > 0, "stone");
		for (Commodity commodity : Commodity.values()) {
			assertTrue(settlement.ledger().reserved(commodity) <= settlement.ledger().onHand(commodity));
		}
	}

	@Test
	void starvingSettlementShrinksButIsNotDeleted() {
		Settlement settlement = settle(4, Surface.SOIL);
		settlement.setSurvey(settlement.siteScore(), Settlement.PlotMap.EMPTY); // nowhere to farm
		List<CitizenLeftEvent> left = new ArrayList<>();
		simulation.events().subscribe(CitizenLeftEvent.class, left::add);

		headless.runDays(20);
		assertEquals(4, left.size());
		assertEquals(0, settlement.size());
		assertNotNull(simulation.state().settlements().get(settlement.id()).orElse(null), "the settlement record remains");
	}

	@Test
	void laborStateSurvivesSaveAndLoad(@TempDir Path dir) throws IOException {
		Settlement settlement = settle(6, Surface.SOIL);
		headless.runTicks(Labor.CYCLE_TICKS * 4 + 3);
		settlement.sites().getFirst().setY(63);
		settlement.sites().getLast().setProgress(17);
		settlement.members().iterator().next().carry(Commodity.STONE, 5);

		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());
		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(simulation.state()), store.export(loaded.state()));
		assertEquals(0, store.save(loaded.state()));

		Settlement restored = loaded.state().settlements().get(settlement.id()).orElseThrow();
		assertEquals(3, restored.sites().size());
		assertEquals(settlement.sites().getFirst().directionX(), restored.sites().getFirst().directionX());
		assertEquals(settlement.members().stream().filter(c -> c.job() != null).count(),
				restored.members().stream().filter(c -> c.job() != null).count());
	}
}
