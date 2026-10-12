package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Fortification;
import com.sunnatbek.villagerciv.build.WallPiece;
import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefenseTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(606);
	private final Simulation simulation = headless.simulation();

	private Settlement settle(int members, Policy... plans) {
		for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members; v++) {
			simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		for (Policy plan : plans) {
			simulation.adoptPlan(settlement, plan);
		}
		return settlement;
	}

	/** Runs until the camp has become a Settlement. */
	private void growToSettlement(Settlement settlement) {
		for (int day = 0; day < 40 && settlement.stage() == SettlementStage.FOUNDING_CAMP; day++) {
			headless.runDays(1);
		}
		assertTrue(settlement.stage().ordinal() >= SettlementStage.SETTLEMENT.ordinal());
	}

	@Test
	void theWallIsAClosedRingWithAGateOnEverySideAndATowerOnEveryCorner() {
		Settlement settlement = settle(8, Policy.AGRARIAN);
		headless.runDays(6);
		List<WallPiece> pieces = Fortification.plan(settlement, simulation.blueprints());

		assertEquals(4, pieces.stream().filter(p -> p.blueprint().equals(Fortification.GATE)).count());
		assertEquals(4, pieces.stream().filter(p -> p.blueprint().equals(Fortification.TOWER)).count());
		assertEquals(Fortification.GATE, pieces.getFirst().blueprint(), "gates first");
		assertTrue(pieces.get(4).blueprint().equals(Fortification.TOWER), "then towers");

		// Work out the ring from the pieces, then check every cell of it is covered exactly once.
		int radius = pieces.stream().mapToInt(p -> Math.max(
				Math.max(Math.abs(p.x()), Math.abs(p.x() + p.sizeX() - 1)), Math.max(Math.abs(p.z()), Math.abs(p.z() + p.sizeZ() - 1)))).max()
				.orElseThrow() - 2;
		Map<Long, Integer> covered = new HashMap<>();
		for (WallPiece piece : pieces) {
			for (int x = piece.x(); x < piece.x() + piece.sizeX(); x++) {
				for (int z = piece.z(); z < piece.z() + piece.sizeZ(); z++) {
					if (Math.max(Math.abs(x), Math.abs(z)) == radius) {
						covered.merge(((long) x << 32) | (z & 0xFFFFFFFFL), 1, Integer::sum);
					}
				}
			}
		}
		assertEquals(8 * radius, covered.size(), "no gaps in a ring of radius " + radius);
		assertTrue(covered.values().stream().allMatch(count -> count == 1), "and no piece overlaps another");

		// Everything the settlement has is inside.
		for (Building building : settlement.buildings()) {
			assertTrue(Math.abs(building.x()) < radius - 2 && Math.abs(building.x() + building.sizeX()) < radius - 2
					&& Math.abs(building.z()) < radius - 2 && Math.abs(building.z() + building.sizeZ()) < radius - 2);
		}
		for (WorkSite site : settlement.sites()) {
			assertTrue(Math.abs(site.x()) + WorkSite.HALF_WIDTH < radius && Math.abs(site.z()) + WorkSite.HALF_WIDTH < radius);
		}
		// Every piece's design exists, including the made-to-measure stretches of palisade.
		for (WallPiece piece : pieces) {
			Blueprint blueprint = simulation.blueprints().get(piece.blueprint()).orElseThrow();
			assertEquals(Blueprint.Category.DEFENSE, blueprint.category());
			assertEquals(piece.sizeX() * piece.sizeZ(), blueprint.width() * blueprint.depth());
		}
	}

	@Test
	void aPeacefulSettlementBuildsNoWallAndKeepsATokenWatch() {
		Settlement settlement = settle(8, Policy.AGRARIAN);
		growToSettlement(settlement);
		headless.runDays(5);

		assertEquals(0, settlement.wallPieces(), "nothing has attacked it: wood goes into houses");
		assertTrue(settlement.guards() >= 1 && settlement.guards() <= 2, "guards: " + settlement.guards());
		assertEquals(0, settlement.threat());
	}

	@Test
	void attacksRaiseTheMilitiaAndThenAWall() {
		Settlement settlement = settle(8, Policy.AGRARIAN);
		growToSettlement(settlement);
		headless.runDays(1);
		int guardsBefore = settlement.guards();

		// Three deaths: well past the mark even if a day's fading falls in between.
		for (int i = 0; i < 3; i++) {
			Defense.incident(settlement, Defense.KILLED);
		}
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		assertTrue(settlement.guards() > guardsBefore, "more guards after losses");
		assertTrue(settlement.guards() <= (settlement.size() - settlement.children()) / 4, "but never more than a quarter");
		assertTrue(settlement.wallPieces() > 0, "and a wall is planned (threat " + settlement.threat() + ", food days "
				+ settlement.foodDays() + ")");
		assertTrue(settlement.members().stream().filter(Citizen::isGuard)
				.noneMatch(c -> c.isChild() || c.role() == Role.LEADER || c.role() == Role.GENERAL));

		headless.runDays(12);
		assertTrue(settlement.threat() <= 1, "quiet days let the alarm fade: " + settlement.threat());
		long built = settlement.buildings().stream().filter(b -> b.category() == Blueprint.Category.DEFENSE)
				.filter(b -> b.stage() == Building.Stage.COMPLETE).count();
		assertTrue(built >= 8, "gates and towers stand, wall pieces follow: " + built);
		assertEquals(settlement.wallPieces(), settlement.wallPlan().size()
				+ settlement.buildings().stream().filter(b -> b.category() == Blueprint.Category.DEFENSE).count(), "no piece lost or doubled");
		assertFalse(settlement.isStarving(), "the wall did not starve the settlement");
		assertTrue(settlement.members().stream().allMatch(c -> c.home() != Citizen.NO_HOME), "nor take anyone's bed");
	}

	@Test
	void aMilitaryPlanFortifiesWithoutWaitingToBeHurt() {
		Settlement settlement = settle(8, Policy.AGRARIAN, Policy.MILITARY);
		growToSettlement(settlement);
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		assertTrue(settlement.wallPieces() > 0);
		assertEquals(0, settlement.threat());
		assertTrue(Defense.wantedGuards(settlement) >= 2);
	}

	@Test
	void aCampDoesNotFortify() {
		Settlement settlement = settle(6);
		Defense.incident(settlement, 50);
		headless.runDays(2);
		assertEquals(SettlementStage.FOUNDING_CAMP, settlement.stage());
		assertEquals(0, settlement.wallPieces(), "too small and too poor for a wall");
		assertTrue(settlement.guards() <= 1, "at most one of its few hands stands guard beside the General");
	}

	@Test
	void swordsGoToWhoeverFights() {
		Settlement settlement = settle(8, Policy.AGRARIAN, Policy.MILITARY);
		growToSettlement(settlement);
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		Citizen guard = settlement.members().stream().filter(Citizen::isGuard).findFirst().orElseThrow();
		assertEquals(settlement.guards() + 1, Tools.needed(settlement, ToolKind.SWORD), "guards and the General");

		settlement.contributeTools(PLAYER, ToolKind.SWORD, 10);
		assertTrue(Tools.equipped(settlement, guard, ToolKind.SWORD));
	}

	@Test
	void defenceStateSurvivesSaveAndLoad(@TempDir Path dir) throws IOException {
		Settlement settlement = settle(8, Policy.AGRARIAN, Policy.MILITARY);
		growToSettlement(settlement);
		headless.runDays(2);
		Defense.incident(settlement, 7);

		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());
		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(simulation.state()), store.export(loaded.state()));
		Settlement restored = loaded.state().settlements().get(settlement.id()).orElseThrow();
		assertEquals(settlement.wallPlan(), restored.wallPlan());
		assertEquals(settlement.wallPieces(), restored.wallPieces());
		assertEquals(settlement.guards(), restored.guards());
		assertEquals(settlement.threat(), restored.threat());
	}

	@Test
	void aWalledSettlementFillsUpAndThenLivesOnWithoutGrowing() {
		HeadlessSimulation headless = new HeadlessSimulation(77);
		Simulation simulation = headless.simulation();
		for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < 8; v++) {
			simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, TerrainSnapshot.Surface.SOIL);
		}
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		simulation.adoptPlan(settlement, Policy.MILITARY);
		simulation.adoptPlan(settlement, Policy.AGRARIAN);
		settlement.ledger().add(Commodity.WOOD, 200_000);
		settlement.ledger().add(Commodity.STONE, 200_000);
		settlement.ledger().add(Commodity.FOOD, 200_000);

		headless.runDays(60);
		assertTrue(settlement.wallPieces() > 0, "it has a wall");
		for (Building building : settlement.buildings()) {
			if (building.category() != Blueprint.Category.DEFENSE) {
				assertTrue(settlement.withinWalls(building.x(), building.z())
						&& settlement.withinWalls(building.x() + building.sizeX() - 1, building.z() + building.sizeZ() - 1),
						"building " + building.id() + " (" + building.blueprint() + ") stands inside the wall");
			}
		}
		for (WorkSite site : settlement.sites()) {
			assertTrue(settlement.withinWalls(site.x(), site.z()), "work sites too");
		}
		// Left alone for another month it is the same place: full, fed, at work, and no bigger.
		int size = settlement.size();
		int buildings = settlement.buildings().size();
		assertTrue(size <= settlement.housingCapacity(), "nobody without a bed: " + size + " of " + settlement.housingCapacity());
		headless.runDays(30);
		assertTrue(settlement.size() <= settlement.housingCapacity());
		assertTrue(settlement.size() - size <= settlement.housingCapacity() - size, "it only fills the beds it has");
		assertTrue(settlement.buildings().size() - buildings <= 2, "and builds next to nothing more: " + buildings + " then " + settlement.buildings().size());
		assertFalse(settlement.isStarving());
		assertTrue(settlement.members().stream().anyMatch(c -> c.job() != null), "people keep to their work");
	}

	@Test
	void finishedTowersAreMannedAndDefendersArmThemselvesFromTheOre() {
		HeadlessSimulation headless = new HeadlessSimulation(78);
		Simulation simulation = headless.simulation();
		for (long v = 0; simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < 8; v++) {
			simulation.recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, TerrainSnapshot.Surface.SOIL);
		}
		Settlement settlement = simulation.found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		simulation.adoptPlan(settlement, Policy.MILITARY);
		simulation.adoptPlan(settlement, Policy.AGRARIAN);
		settlement.ledger().add(Commodity.WOOD, 200_000);
		settlement.ledger().add(Commodity.STONE, 200_000);
		settlement.ledger().add(Commodity.FOOD, 200_000);
		headless.runDays(40);

		long towers = settlement.buildings().stream()
				.filter(b -> b.blueprint().equals(Fortification.TOWER) && b.stage() == Building.Stage.COMPLETE).count();
		assertEquals(4, towers, "the wall is up, towers and all");
		List<Citizen> posted = settlement.members().stream().filter(c -> c.post() != Citizen.NO_POST).toList();
		long patrols = settlement.members().stream().filter(c -> c.patrol() != Citizen.Patrol.NONE).count();
		assertEquals(settlement.guards(), posted.size() + patrols, "every guard has a place on the wall or a round to walk");
		assertTrue(patrols <= posted.size(), "no more on patrol than on the wall: " + patrols + " and " + posted.size());
		assertTrue(settlement.members().stream().filter(c -> c.patrol() != Citizen.Patrol.NONE).allMatch(c -> c.job() == null && c.isGuard()),
				"and a patrol does nothing else");
		assertTrue(posted.size() >= towers, "a guard to each tower first: " + posted.size());
		assertEquals(posted.size(), posted.stream().map(c -> c.post() * 10 + c.postPlace()).distinct().count(), "no two in one place");
		long adults = settlement.size() - settlement.children();
		assertTrue(settlement.guards() <= adults / 2, "never more than half of the grown citizens: " + settlement.guards() + " of " + adults);
		long gates = settlement.buildings().stream().filter(b -> b.blueprint().equals(Fortification.GATE)).count();
		if (settlement.guards() >= towers + gates) {
			assertEquals(gates, posted.stream().filter(c -> settlement.building(c.post()).blueprint().equals(Fortification.GATE)).count(),
					"then one above every gate");
		}
		assertTrue(posted.stream().allMatch(c -> c.isGuard() && c.job() == null), "a posted guard has no other job");

		// A guard who falls is replaced from among the citizens, post and all.
		int guards = settlement.guards();
		Citizen fallen = posted.getFirst();
		int post = fallen.post();
		int place = fallen.postPlace();
		simulation.citizenDied(settlement, fallen.id());
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		assertEquals(Defense.wantedGuards(settlement), settlement.guards(), "the watch is brought back up to strength (was " + guards + ")");
		assertTrue(settlement.members().stream().anyMatch(c -> c.post() == post && c.postPlace() == place), "and the empty post is taken");

		assertTrue(settlement.members().stream().noneMatch(Citizen::isArmored), "no ore, no armour");
		long defenders = settlement.members().stream().filter(Citizen::isDefender).count();
		// A settlement with a smith at work makes each suit from two thirds of the ore.
		int perSuit = settlement.hasWorker(JobType.SMITH) ? Defense.ARMOR_ORE * 2 / 3 : Defense.ARMOR_ORE;
		if (settlement.ledger().onHand(Commodity.ORE) > 0) {
			settlement.ledger().take(Commodity.ORE, settlement.ledger().onHand(Commodity.ORE));
		}
		settlement.ledger().add(Commodity.ORE, perSuit * (int) defenders + 5);
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		assertEquals(defenders, settlement.members().stream().filter(Citizen::isArmored).count(), "a suit for every defender");
		assertTrue(settlement.ledger().onHand(Commodity.ORE) <= 5, "paid for in ore: " + settlement.ledger().onHand(Commodity.ORE) + " left");
	}
}
