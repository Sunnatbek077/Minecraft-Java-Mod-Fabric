package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Expedition;
import com.sunnatbek.villagerciv.sim.Founding;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.SettlementStage;
import com.sunnatbek.villagerciv.sim.SiteSurvey;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import com.sunnatbek.villagerciv.sim.WorkSite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(515);
	private final BlueprintLibrary library = headless.simulation().blueprints();

	private Settlement settle(int members) {
		for (long v = 0; headless.simulation().expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members; v++) {
			headless.simulation().recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		return headless.simulation().found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
	}

	// --- blueprints ---

	@Test
	void builtInBlueprintsAreWellFormed() {
		assertTrue(library.all().size() >= 3);
		assertTrue(library.get(Fortification.GATE).isPresent() && library.get(Fortification.TOWER).isPresent());
		for (Blueprint.Category category : Blueprint.Category.values()) {
			assertFalse(library.ofCategory(category).isEmpty(), "at least one design per category: " + category);
		}
		for (Blueprint blueprint : library.all()) {
			String id = blueprint.id();
			// The main storehouse has a lot set aside for it, and a city's gatehouse stands in its wall, not on a lot.
			int widest = id.startsWith(Blueprint.MAIN_STORE) ? 25 : id.equals(Fortification.GREAT_GATE) ? 14 : 13;
			assertTrue(blueprint.width() <= widest && blueprint.depth() <= widest, id + " fits a lot");
			int doors = 0;
			int bedHeads = 0;
			int bedFeet = 0;
			for (int y = 0; y < blueprint.height(); y++) {
				for (int z = 0; z < blueprint.depth(); z++) {
					for (int x = 0; x < blueprint.width(); x++) {
						char cell = blueprint.cell(x, y, z);
						if (cell == Blueprint.LEAVE || cell == Blueprint.CLEAR) {
							continue;
						}
						String state = blueprint.palette().get(cell);
						assertTrue(state.matches("minecraft:[a-z_]+(\\[[a-z_]+=[a-z_0-9]+(,[a-z_]+=[a-z_0-9]+)*])?"), id + ": " + state);
						if (state.contains("_door[half=lower")) {
							doors++;
							assertTrue(blueprint.isLate(cell), id + ": doors go in last");
							assertTrue(blueprint.palette().get(blueprint.cell(x, y + 1, z)).contains("_door[half=upper"), id + ": door top above bottom");
							// The main storehouse stands back from the street, behind its yard.
							// Behind at most a porch, a strip of garden and the steps up to it.
							assertTrue(z >= blueprint.depth() - 4 || id.startsWith(Blueprint.MAIN_STORE), id + ": the door is in the front wall");
						}
						if (state.contains("_bed[") && state.contains("part=head")) {
							bedHeads++;
							assertTrue(blueprint.palette().get(blueprint.cell(x, y, z + 1)).contains("part=foot"), id + ": bed foot south of its head");
						}
						if (state.contains("part=foot")) {
							bedFeet++;
						}
						if (state.contains("hanging=true")) {
							char above = blueprint.cell(x, y + 1, z);
							assertTrue(above != Blueprint.LEAVE && above != Blueprint.CLEAR, id + ": a hanging lantern needs something to hang from");
						}
					}
				}
			}
			assertEquals(blueprint.beds(), bedHeads, id + ": bed count matches the beds drawn");
			assertEquals(bedHeads, bedFeet, id);
			// Everything people go into closes with a door: a home must keep the night out. So does
			// a gate. Only a sealed tower has none.
			// Nor do things that are not gone into at all: a well, a fountain.
			if (Blueprint.isPen(id)) {
				// A pen closes with a gate, and keeps animals in rather than the night out.
				assertTrue(blueprint.palette().values().stream().anyMatch(state -> state.contains("_fence_gate[")), id + " has a gate");
				char middle = blueprint.cell(blueprint.width() / 2, 1, blueprint.depth() / 2);
				assertEquals(Blueprint.CLEAR, middle, id + ": the middle is bare, for the animals to be set down on");
			} else if (!Fortification.isTower(id) && !id.equals("well") && !id.equals("fountain")) {
				assertTrue(doors >= 1, id + " has a door");
			}
			assertEquals(blueprint.beds(), blueprint.bedHeads().size(), id);
			assertTrue(blueprint.cost(Commodity.WOOD) > 0, id + " costs something");
		}
	}

	@Test
	void malformedBlueprintsAreRejected() {
		String head = "{\"id\":\"x\",\"name\":\"X\",\"category\":\"housing\",\"cost\":{\"wood\":1},\"palette\":{\"A\":\"minecraft:stone\"},";
		for (String layers : List.of(
				"\"layers\":[[\"AA\",\"A\"]]}",          // ragged row
				"\"layers\":[[\"AA\"],[\"AA\",\"AA\"]]}", // layers of different depth
				"\"layers\":[[\"AB\"]]}",                 // unknown key
				"\"layers\":[]}")) {
			try {
				BlueprintLibrary.parse(com.google.gson.JsonParser.parseString(head + layers).getAsJsonObject());
				throw new AssertionError("accepted: " + layers);
			} catch (IllegalArgumentException expected) {
				// rejected, as it should be
			}
		}
	}

	@Test
	void rotationTurnsTheFrontTowardsEachSide() {
		Blueprint blueprint = library.get("storehouse").orElseThrow();
		int frontX = blueprint.width() / 2;
		int frontZ = blueprint.depth() - 1;
		assertEquals(blueprint.depth() - 1, ConstructionPlan.rotatedZ(blueprint, 0, frontX, frontZ), "south");
		assertEquals(0, ConstructionPlan.rotatedX(blueprint, 1, frontX, frontZ), "west");
		assertEquals(0, ConstructionPlan.rotatedZ(blueprint, 2, frontX, frontZ), "north");
		assertEquals(blueprint.depth() - 1, ConstructionPlan.rotatedX(blueprint, 3, frontX, frontZ), "east");

		// Every rotation maps the cells one-to-one onto the rotated footprint.
		for (int rotation = 0; rotation < 4; rotation++) {
			Set<Long> seen = new HashSet<>();
			int sizeX = rotation % 2 == 0 ? blueprint.width() : blueprint.depth();
			int sizeZ = rotation % 2 == 0 ? blueprint.depth() : blueprint.width();
			for (int z = 0; z < blueprint.depth(); z++) {
				for (int x = 0; x < blueprint.width(); x++) {
					int rx = ConstructionPlan.rotatedX(blueprint, rotation, x, z);
					int rz = ConstructionPlan.rotatedZ(blueprint, rotation, x, z);
					assertTrue(rx >= 0 && rx < sizeX && rz >= 0 && rz < sizeZ);
					assertTrue(seen.add(((long) rx << 32) | rz));
				}
			}
		}
	}

	// --- town plan ---

	@Test
	void buildingsStandAlongStreetsFacingThemAndNeverOverlap() {
		Settlement settlement = settle(6);
		headless.runTicks(Labor.CYCLE_TICKS * 2); // work sites first, as in play
		Blueprint cottage = library.get("cottage").orElseThrow();
		for (int i = 0; i < 12; i++) {
			assertNotNull(TownPlan.site(settlement, cottage, headless.simulation().state().ids()), "lot " + i);
		}

		assertTrue(settlement.streets().size() >= 2, "one street cannot hold twelve cottages");
		List<Building> buildings = settlement.buildings();
		for (Building a : buildings) {
			Street street = settlement.streets().get(a.street());
			// The front of the footprint lies along the street, one verge cell away from its edge.
			int[] front = frontCell(a);
			assertTrue(street.covers(front[0] + frontDirection(a)[0] * 2, front[1] + frontDirection(a)[1] * 2),
					"building " + a.id() + " faces its street");
			for (Building b : buildings) {
				assertTrue(a == b || a.x() + a.sizeX() < b.x() || b.x() + b.sizeX() < a.x()
						|| a.z() + a.sizeZ() < b.z() || b.z() + b.sizeZ() < a.z(), "buildings keep a gap");
			}
			for (WorkSite site : settlement.sites()) {
				assertFalse(a.contains(site.x(), site.z()), "not on a work site");
			}
			for (Street other : settlement.streets()) {
				for (int dx = 0; dx < a.sizeX(); dx++) {
					for (int dz = 0; dz < a.sizeZ(); dz++) {
						assertFalse(other.covers(a.x() + dx, a.z() + dz), "not on a street");
					}
				}
			}
			assertFalse(a.contains(settlement.anchorX(), settlement.anchorZ()));
		}
	}

	private static int[] frontDirection(Building building) {
		return switch (building.rotation()) {
			case 0 -> new int[] {0, 1};
			case 1 -> new int[] {-1, 0};
			case 2 -> new int[] {0, -1};
			default -> new int[] {1, 0};
		};
	}

	/** Middle cell of the footprint's front edge. */
	private static int[] frontCell(Building building) {
		int midX = building.x() + building.sizeX() / 2;
		int midZ = building.z() + building.sizeZ() / 2;
		return switch (building.rotation()) {
			case 0 -> new int[] {midX, building.z() + building.sizeZ() - 1};
			case 1 -> new int[] {building.x(), midZ};
			case 2 -> new int[] {midX, building.z()};
			default -> new int[] {building.x() + building.sizeX() - 1, midZ};
		};
	}

	@Test
	void sameSeedLaysOutTheSameTown() {
		assertEquals(layout(), layout());
	}

	private static String layout() {
		ConstructionTest fresh = new ConstructionTest();
		Settlement settlement = fresh.settle(6);
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 5; i++) {
			Building building = TownPlan.site(settlement, fresh.library.get("cottage").orElseThrow(), fresh.headless.simulation().state().ids());
			text.append(building.x()).append(',').append(building.z()).append(',').append(building.rotation()).append(';');
		}
		return text.toString();
	}

	// --- construction ---

	@Test
	void planCoversPavingLevellingAndEveryCellInOrder() {
		Settlement settlement = settle(4);
		Blueprint cottage = library.get("cottage").orElseThrow();
		Building building = TownPlan.site(settlement, cottage, headless.simulation().state().ids());
		List<ConstructionPlan.Step> steps = ConstructionPlan.of(building, cottage, settlement.streets().get(building.street()));

		assertEquals(building.totalSteps(), steps.size(), "the stored step count matches the plan");
		int firstLevel = indexOfFirst(steps, ConstructionPlan.Kind.LEVEL);
		int firstPlace = indexOfFirst(steps, ConstructionPlan.Kind.PLACE);
		assertTrue(indexOfFirst(steps, ConstructionPlan.Kind.PAVE) < firstLevel && firstLevel < firstPlace, "pave, then level, then build");
		assertEquals(building.sizeX() * building.sizeZ(), steps.stream().filter(s -> s.kind() == ConstructionPlan.Kind.LEVEL).count(),
				"the whole footprint is levelled");

		int lastOrdinary = -1;
		int firstLate = steps.size();
		for (int i = firstPlace; i < steps.size(); i++) {
			ConstructionPlan.Step step = steps.get(i);
			assertTrue(building.contains(step.x(), step.z()), "nothing is placed outside the lot");
			boolean late = step.state() != null && (step.state().contains("_door") || step.state().contains("_bed") || step.state().contains("lantern")
					|| step.state().contains(":chest"));
			if (late) {
				firstLate = Math.min(firstLate, i);
			} else {
				lastOrdinary = i;
			}
		}
		assertTrue(lastOrdinary < firstLate, "doors, beds, lanterns and chests come after everything they lean on");
	}

	private static int indexOfFirst(List<ConstructionPlan.Step> steps, ConstructionPlan.Kind kind) {
		for (int i = 0; i < steps.size(); i++) {
			if (steps.get(i).kind() == kind) {
				return i;
			}
		}
		return Integer.MAX_VALUE;
	}

	@Test
	void settlementBuildsShelterStoreAndABedForEveryoneWithoutThePlayer() {
		Settlement settlement = settle(6);
		settlement.contribute(PLAYER, Commodity.WOOD, 2000);
		settlement.contribute(PLAYER, Commodity.STONE, 1000);

		headless.runDays(6);

		assertTrue(settlement.hasBuilding(Blueprint.Category.SHELTER));
		assertTrue(settlement.hasBuilding(Blueprint.Category.STORAGE));
		assertEquals(settlement.mainStore(), settlement.buildings().getFirst(), "the storehouse's lot is set aside before anything");
		assertEquals(Blueprint.Category.SHELTER, settlement.buildings().get(1).category(), "but a roof is built first");
		assertTrue(settlement.plannedBeds() >= settlement.size(), "a bed for everyone: " + settlement.plannedBeds());
		long complete = settlement.buildings().stream().filter(b -> b.stage() == Building.Stage.COMPLETE).count();
		assertTrue(complete >= 5, "at least five buildings stand: " + complete);
		// Everyone has a bed of their own.
		Set<String> beds = new HashSet<>();
		for (com.sunnatbek.villagerciv.sim.Citizen citizen : settlement.members()) {
			Building home = settlement.building(citizen.home());
			assertNotNull(home, citizen.name() + " has a home");
			assertTrue(home.stage() != Building.Stage.UNDER_CONSTRUCTION && citizen.bed() < home.beds());
			assertTrue(beds.add(citizen.home() + ":" + citizen.bed()), "no bed is shared");
		}

		// Nobody was near, so nothing is in the world yet: all of it waits as deferred edits.
		for (Building building : settlement.buildings()) {
			assertEquals(0, building.applied());
			if (building.stage() == Building.Stage.COMPLETE) {
				assertEquals(building.totalSteps(), building.progress());
			}
		}
		// Only the project in hand holds a reservation, and for exactly its cost.
		Building active = settlement.activeBuilding();
		assertEquals(active == null ? 0 : active.costWood(), settlement.ledger().reserved(Commodity.WOOD));
		assertEquals(active == null ? 0 : active.costStone(), settlement.ledger().reserved(Commodity.STONE));
	}

	@Test
	void materialsAreReservedWhileBuildingAndNothingStartsWithoutThem() {
		Settlement settlement = settle(6);
		headless.runTicks(Labor.CYCLE_TICKS * 3);
		assertEquals(0, settlement.buildings().stream().filter(b -> b.stage() != Building.Stage.PLANNED).count(), "no wood, no building");

		settlement.contribute(PLAYER, Commodity.WOOD, 65);
		headless.runTicks(Labor.CYCLE_TICKS * 2);
		Building shelter = settlement.activeBuilding();
		assertNotNull(shelter);
		assertEquals(shelter.costWood(), settlement.ledger().reserved(Commodity.WOOD));
		assertTrue(settlement.ledger().available(Commodity.WOOD) < 65, "reserved wood cannot be spent on anything else");
		assertTrue(settlement.members().stream().anyMatch(c -> c.job() == JobType.BUILD && c.jobSite() == shelter.id()));
	}

	@Test
	void constructionResumesAfterSaveAndLoadFromTheSameStep(@TempDir Path dir) throws IOException {
		Settlement settlement = settle(6);
		settlement.contribute(PLAYER, Commodity.WOOD, 2000);
		settlement.contribute(PLAYER, Commodity.STONE, 1000);
		headless.runTicks(Labor.CYCLE_TICKS * 8);
		Building active = settlement.activeBuilding();
		assertNotNull(active);
		assertTrue(active.progress() > 0 && active.progress() < active.totalSteps(), "caught mid-construction");
		active.setY(64);
		active.stepApplied();

		SaveStore store = new SaveStore(dir);
		store.save(headless.simulation().state());
		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(headless.simulation().state()), store.export(loaded.state()));

		Settlement restored = loaded.state().settlements().get(settlement.id()).orElseThrow();
		Building same = restored.activeBuilding();
		assertEquals(active.progress(), same.progress());
		assertEquals(1, same.applied());
		assertEquals(64, same.y());
		assertEquals(settlement.streets().size(), restored.streets().size());
		Blueprint blueprint = library.get(same.blueprint()).orElseThrow();
		assertEquals(ConstructionPlan.of(active, blueprint, settlement.streets().get(active.street())),
				ConstructionPlan.of(same, blueprint, restored.streets().get(same.street())), "the same steps come out again");
	}

	@Test
	void aCottageHasAChestForEachBedAndAnOlderOneTakesTheNewDesign() {
		Blueprint cottage = library.get("cottage").orElseThrow();
		assertEquals(cottage.bedHeads().size(), cottage.chests().size());
		for (Blueprint.Cell chest : cottage.chests()) {
			// Clear of the bedside, where the sleeper stands before lying down.
			assertTrue(cottage.bedHeads().stream().noneMatch(head -> head.x() == chest.x() && chest.z() - head.z() <= 2));
		}

		Settlement settlement = settle(4);
		Building building = TownPlan.site(settlement, cottage, headless.simulation().state().ids());
		building.setCursors(building.totalSteps(), building.totalSteps());
		building.redesigned(building.totalSteps() + 2);
		assertTrue(building.isWorkDone(), "finished work stays finished");
		assertTrue(building.applied() < building.totalSteps(), "but the additions still have to be put in");
	}

	@Test
	void everyHouseDesignCanBeLivedIn() {
		List<Blueprint> houses = library.ofCategory(Blueprint.Category.HOUSING);
		assertTrue(houses.size() >= 11, "variety: " + houses.size());
		for (Blueprint house : houses) {
			String id = house.id();
			List<Blueprint.Cell> heads = house.bedHeads();
			assertEquals(house.beds(), heads.size(), id + ": as many beds as it says");
			assertEquals(heads.size(), house.chests().size(), id + ": a chest for each bed");
			int door = -1;
			// The floor people live on: the ground floor, or the deck of a house on stilts.
			int floor = heads.isEmpty() ? 1 : heads.getFirst().y();
			for (int x = 0; x < house.width(); x++) {
				for (int z = 0; z < house.depth(); z++) {
					char cell = house.cell(x, floor, z);
					if (cell != Blueprint.LEAVE && cell != Blueprint.CLEAR && house.palette().get(cell).contains("_door[half=lower")) {
						door = x;
						assertEquals(Blueprint.CLEAR, house.cell(x, floor, z - 1), id + ": the way in is open");
						assertEquals(Blueprint.CLEAR, house.cell(x, floor + 1, z - 1), id + ": with headroom");
					}
				}
			}
			assertTrue(door >= 0, id + ": has a door");
			for (Blueprint.Cell head : heads) {
				assertEquals(floor, head.y(), id + ": beds stand on the floor, all on the same one");
				char under = house.cell(head.x(), floor - 1, head.z());
				assertTrue(under != Blueprint.LEAVE && under != Blueprint.CLEAR, id + ": with a floor under them");
				assertTrue(house.palette().get(house.cell(head.x(), floor, head.z() + 1)).contains("part=foot"), id + ": foot towards the door");
				// The bedside, and a way from it to the door column, is kept clear.
				int bedside = head.z() + 2;
				for (int x = Math.min(head.x(), door); x <= Math.max(head.x(), door); x++) {
					assertEquals(Blueprint.CLEAR, house.cell(x, floor, bedside), id + ": bedside row is free at " + x);
				}
				assertTrue(house.chests().stream().noneMatch(chest -> chest.x() == head.x() && chest.z() <= bedside), id + ": no chest on the bedside");
			}
			assertTrue(house.cost(Commodity.WOOD) > 0, id);
		}
	}

	@Test
	void theWallGoesUpAlongsideAHouse() {
		Settlement settlement = settle(8);
		headless.simulation().adoptPlan(settlement, Policy.MILITARY);
		settlement.setStage(SettlementStage.SETTLEMENT);
		settlement.ledger().add(Commodity.FOOD, 2_000);
		settlement.ledger().add(Commodity.WOOD, 5_000);
		settlement.ledger().add(Commodity.STONE, 3_000);

		boolean together = false;
		for (int step = 0; step < 400 && !together; step++) {
			headless.runTicks(Labor.CYCLE_TICKS);
			together = settlement.activeBuilding() != null && settlement.activeWallPiece() != null;
			if (together) {
				long builders = settlement.members().stream().filter(c -> c.job() == JobType.BUILD).count();
				assertTrue(builders >= 2, "each site has hands: " + builders);
			}
		}
		assertTrue(together, "a house and a piece of wall are under way at once");
		int planned = settlement.wallPieces();
		headless.runDays(6);
		long built = settlement.buildings().stream()
				.filter(b -> b.category() == Blueprint.Category.DEFENSE && b.stage() == Building.Stage.COMPLETE).count();
		assertTrue(built >= 4, "the wall makes headway: " + built + " of " + planned);
	}

	@Test
	void theMainStorehouseGrowsOnOneLotWithoutUndoingItself() {
		Blueprint previous = null;
		for (int tier = 1; tier <= 3; tier++) {
			Blueprint store = library.get(Blueprint.MAIN_STORE + tier).orElseThrow();
			assertEquals(25, store.width());
			assertEquals(25, store.depth());
			Set<Commodity> kept = new HashSet<>();
			store.storeCells().forEach(cell -> kept.add(cell.commodity()));
			assertEquals(Set.of(Commodity.values()), kept, "tier " + tier + " has a section for everything");
			if (previous != null) {
				assertTrue(store.storeCells().size() >= previous.storeCells().size(), "tier " + tier + " holds no less");
				for (int y = 0; y < previous.height(); y++) {
					for (int z = 0; z < 25; z++) {
						for (int x = 0; x < 25; x++) {
							char before = previous.cell(x, y, z);
							char after = store.cell(x, y, z);
							// Whatever stood stays, except where a way is opened or a space is furnished.
							assertTrue(before == after || before == Blueprint.LEAVE || before == Blueprint.CLEAR || after == Blueprint.CLEAR,
									"tier " + tier + " at " + x + "," + y + "," + z + ": '" + before + "' became '" + after + "'");
						}
					}
				}
			}
			previous = store;
		}
	}

	@Test
	void aNewSettlementRaisesItsMainStorehouseAndEnlargesItAsItGrows() {
		Settlement settlement = settle(8);
		headless.simulation().adoptPlan(settlement, Policy.INDUSTRY);
		settlement.ledger().add(Commodity.FOOD, 5_000);
		settlement.ledger().add(Commodity.WOOD, 8_000);
		settlement.ledger().add(Commodity.STONE, 8_000);
		headless.runDays(3);

		Building store = settlement.mainStore();
		assertNotNull(store, "the store a camp builds is the main storehouse");
		assertEquals(25, store.sizeX());
		assertEquals(25, store.sizeZ());
		assertEquals(1, settlement.buildings().stream().filter(b -> b.category() == Blueprint.Category.STORAGE).count());

		for (int day = 0; day < 30 && !store.blueprint().equals(Blueprint.MAIN_STORE + 3); day++) {
			headless.runDays(1);
			if (store.stage() == Building.Stage.COMPLETE) {
				Blueprint design = library.get(store.blueprint()).orElseThrow();
				assertEquals(ConstructionPlan.of(store, design, settlement.streets().get(store.street())).size(), store.totalSteps(),
						"step count matches the design after enlarging to " + store.blueprint());
			}
		}
		assertEquals(Blueprint.MAIN_STORE + 3, store.blueprint(), "by the time it is a village: " + settlement.stage());
		assertEquals(0, settlement.ledger().reserved(Commodity.WOOD) - settlement.activeBuildings().stream().mapToInt(Building::costWood).sum(),
				"nothing stays reserved for work that is finished");
	}

	@Test
	void aGrownSettlementFindsRoomForItsStorehouseOffTheStreets() {
		Settlement settlement = settle(6);
		Blueprint cottage = library.get("cottage").orElseThrow();
		for (int i = 0; i < 12; i++) {
			TownPlan.site(settlement, cottage, headless.simulation().state().ids());
		}
		Blueprint hall = library.get(Blueprint.MAIN_STORE + 1).orElseThrow();
		Building store = TownPlan.freeSite(settlement, hall, 48);
		assertNotNull(store, "there is land enough");
		assertEquals(-1, store.street());
		for (Building other : settlement.buildings()) {
			assertTrue(other == store || other.x() + other.sizeX() < store.x() || store.x() + store.sizeX() < other.x()
					|| other.z() + other.sizeZ() < store.z() || store.z() + store.sizeZ() < other.z(), "clear of building " + other.id());
		}
		for (Street street : settlement.streets()) {
			for (int along = 0; along < street.length(); along++) {
				assertFalse(store.contains(street.cellX(along, 0), street.cellZ(along, 0)), "clear of the streets");
			}
		}
		// Its front is the side nearest the Town Center.
		int[] front = frontDirection(store);
		int toCenterX = settlement.anchorX() - (store.x() + store.sizeX() / 2);
		int toCenterZ = settlement.anchorZ() - (store.z() + store.sizeZ() / 2);
		assertTrue(front[0] * toCenterX + front[1] * toCenterZ > 0, "it faces the Town Center");
		assertEquals(ConstructionPlan.of(store, hall, null).size(), store.totalSteps());
	}

	@Test
	void theHallAloneIsACompleteStorehouseThatStaysAsItIs() {
		Blueprint hallOnly = library.get(Blueprint.MAIN_STORE + 0).orElseThrow();
		Blueprint hall = library.get(Blueprint.MAIN_STORE + 1).orElseThrow();
		assertTrue(hallOnly.width() <= 13 && hallOnly.depth() <= 15, "small enough for a crowded settlement");
		assertEquals(hall.storeCells().size(), hallOnly.storeCells().size(), "the same chests as the hall of the full design");

		Settlement settlement = settle(8);
		headless.simulation().adoptPlan(settlement, Policy.INDUSTRY);
		settlement.ledger().add(Commodity.FOOD, 5_000);
		settlement.ledger().add(Commodity.WOOD, 8_000);
		settlement.ledger().add(Commodity.STONE, 8_000);
		// A settlement whose main storehouse is the hall alone.
		Building lot = settlement.mainStore();
		if (lot != null) {
			settlement.buildings().remove(lot);
		}
		Building store = TownPlan.freeSite(settlement, hallOnly, 48);
		assertNotNull(store);
		settlement.setStage(SettlementStage.VILLAGE);
		headless.runDays(6);
		assertEquals(Building.Stage.COMPLETE, store.stage());
		assertEquals(Blueprint.MAIN_STORE + 0, store.blueprint(), "it has no lot to grow on, so it is never enlarged");
	}

	@Test
	void anEnlargedTownBuildsOutThroughItsGatesInsideASecondWallOfStone() {
		Settlement settlement = settle(8);
		headless.simulation().adoptPlan(settlement, Policy.MILITARY);
		headless.simulation().adoptPlan(settlement, Policy.AGRARIAN);
		settlement.ledger().add(Commodity.FOOD, 500_000);
		settlement.ledger().add(Commodity.WOOD, 500_000);
		settlement.ledger().add(Commodity.STONE, 500_000);
		settlement.ledger().add(Commodity.ORE, 20_000);
		// It grows until it is a town, or until the land inside its first wall is full.
		for (int day = 0; day < 80 && (!settlement.mayExpand() || !settlement.wallPlan().isEmpty()); day++) {
			headless.runDays(1);
		}
		assertTrue(settlement.mayExpand(), settlement.stage() + ", size " + settlement.size());
		int firstWall = settlement.wallPieces();
		int housesBefore = settlement.buildings().size();
		int sizeBefore = settlement.size();

		// The wider land: level ground out to the new bounds.
		TerrainSnapshot wide = new TerrainSnapshot(settlement.anchorX(), settlement.anchorZ(), Founding.EXPANDED_RADIUS);
		for (int i = 0; i < wide.columnCount(); i++) {
			wide.set(i, 64, Surface.SOIL);
		}
		assertTrue(headless.simulation().expand(settlement, SiteSurvey.analyse(wide).plots()));
		assertFalse(headless.simulation().expand(settlement, SiteSurvey.analyse(wide).plots()), "a charter is taken once");
		assertTrue(settlement.wallPieces() > firstWall, "a second ring is planned");
		assertTrue(settlement.wallPlan().stream().anyMatch(p -> p.blueprint().equals(Fortification.STONE_GATE)), "with gates of stone");
		assertTrue(settlement.wallPlan().stream().allMatch(p -> library.get(p.blueprint()).isPresent()), "every piece has a design");

		headless.runDays(40);
		long beyond = settlement.buildings().stream().filter(b -> b.category() != Blueprint.Category.DEFENSE
				&& Math.max(Math.abs(b.x() - settlement.anchorX()), Math.abs(b.z() - settlement.anchorZ())) > 50).count();
		assertTrue(beyond >= 5, "it builds beyond the first wall: " + beyond + " buildings, " + settlement.streets().size() + " streets");
		assertTrue(settlement.size() > sizeBefore, "and grows again: " + sizeBefore + " then " + settlement.size());
		assertTrue(settlement.buildings().size() > housesBefore);
		assertTrue(settlement.publicBuildings() >= 2, "with public buildings: " + settlement.publicBuildings());
		long stone = settlement.buildings().stream().filter(b -> b.blueprint().startsWith(WallDesigns.RAMPART_PREFIX)
				&& b.stage() == Building.Stage.COMPLETE).count();
		assertTrue(stone > 0, "and the stone wall is going up");
		for (Building building : settlement.buildings()) {
			assertTrue(settlement.withinWalls(building.x(), building.z()) || building.category() == Blueprint.Category.DEFENSE,
					"nothing outside the second wall: " + building.blueprint());
		}
		assertTrue(settlement.buildings().stream().anyMatch(b -> library.get(b.blueprint()).map(d -> d.minStage() >= 2).orElse(false)),
				"and some of the fine houses among them");
	}
}
