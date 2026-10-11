package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.IdGenerator;
import com.sunnatbek.villagerciv.sim.Ledger;
import com.sunnatbek.villagerciv.sim.Population;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.SettlementStage;

import java.util.List;

/**
 * Decides what a settlement builds next and sees each building through. Two projects can run
 * side by side: one ordinary building, and one piece of the wall if a wall has been planned.
 * Materials are reserved in the ledger when a project starts, so nothing else can spend them,
 * and are used up when it is finished.
 *
 * <p>What comes next is still a fixed order of needs: a roof, somewhere to keep things, a bed
 * for everyone, then houses to grow into. Weighing needs against each other is the Settlement
 * AI's job.
 */
public final class Construction {
	/** What the builders are up to, for showing to the player. */
	public enum Status {
		BUILDING,
		/** Something is wanted, but there is not enough wood or stone for it yet. */
		WAITING_FOR_MATERIALS,
		/** Something is wanted and can be afforded; it starts at the next review, if there is room. */
		PLANNING,
		NOTHING_TO_BUILD,
		/** Something is wanted, but there is nowhere left to put it. */
		NO_ROOM
	}

	private static final int MAIN_STORE_TIERS = 3;
	/** How far from the Town Center a place for the main storehouse is looked for, off the streets. */
	private static final int STORE_SEARCH_REACH = 48;
	private static final int RICH_WOOD = 400;
	private static final int RICH_STONE = 200;

	private Construction() {
	}

	/**
	 * One review: finish whatever has had its work done, then maybe start something new. The
	 * ordinary project and the wall are looked at separately, so neither waits for the other.
	 */
	public static void update(Settlement settlement, BlueprintLibrary library, IdGenerator random) {
		for (Building active : settlement.activeBuildings()) {
			if (active.isWorkDone()) {
				complete(settlement, active);
			}
		}
		if (settlement.activeBuilding() == null) {
			startBuilding(settlement, library, random);
		}
		if (settlement.activeWallPiece() == null && !settlement.wallPlan().isEmpty()) {
			// The wall takes what is left over: materials the next building is waiting for are
			// not spent on it, or a costly building would never get its turn.
			Blueprint waiting = cheapestWanted(settlement, library);
			startWallPiece(settlement, library, waiting == null ? 0 : waiting.cost(Commodity.WOOD),
					waiting == null ? 0 : waiting.cost(Commodity.STONE));
		}
	}

	/**
	 * Sets aside the lot for the main storehouse beside the Town Center, before anything else is
	 * laid out. A newly founded settlement does this first of all, so that its fields, grove and
	 * mine are sited around the lot rather than on it.
	 */
	public static void reserveStoreLot(Settlement settlement, BlueprintLibrary library, IdGenerator random) {
		if (!settlement.buildings().isEmpty() || settlement.storeSoughtAtStreets() >= 0) {
			return;
		}
		Blueprint hall = library.get(Blueprint.MAIN_STORE + 1).orElse(null);
		Building lot = hall == null ? null : TownPlan.site(settlement, hall, random, true, true);
		if (lot == null && hall != null) {
			lot = TownPlan.freeSite(settlement, hall, STORE_SEARCH_REACH);
		}
		if (lot != null) {
			lot.setStage(Building.Stage.PLANNED);
		} else {
			settlement.setStoreSoughtAtStreets(settlement.streets().size());
		}
	}

	private static void startBuilding(Settlement settlement, BlueprintLibrary library, IdGenerator random) {
		Choice choice = want(settlement, library);
		if (choice == null) {
			return;
		}
		if (choice.enlarge() != null) {
			if (canAfford(settlement, choice.design())) {
				if (choice.enlarge().stage() == Building.Stage.PLANNED) {
					choice.enlarge().setStage(Building.Stage.UNDER_CONSTRUCTION);
				} else {
					enlarge(choice.enlarge(), choice.design(), library);
				}
				reserve(settlement, choice.design());
			}
			return;
		}
		Blueprint next = choice.design() != null ? choice.design() : pick(ordinary(library, choice.category()), random);
		if (next == null || !canAfford(settlement, next)) {
			return; // wait for the lumberjacks and miners (or a generous player)
		}
		if (choice.category() == Blueprint.Category.WORKSHOP) {
			// A workshop goes on a street lot like a house, or failing that wherever there is room.
			if (TownPlan.site(settlement, next, random) == null && TownPlan.freeSite(settlement, next, STORE_SEARCH_REACH) == null) {
				settlement.setWorkshopSoughtAtStreets(java.util.Arrays.asList(WORKSHOPS).indexOf(next.id()), settlement.streets().size());
				return;
			}
		} else if (choice.design() != null) {
			// The main storehouse stands as close to the Town Center as there is room for it.
			// Only a settlement with no store at all lays out new streets to fit it in.
			boolean firstStore = !has(settlement, library, Blueprint.Category.STORAGE);
			if (TownPlan.site(settlement, next, random, true, firstStore) == null
					&& TownPlan.freeSite(settlement, next, STORE_SEARCH_REACH) == null) {
				// A settlement that has grown past the point of having so much room in one piece
				// builds the hall alone, on a lot of the hall's own size.
				Blueprint hallOnly = library.get(Blueprint.MAIN_STORE + 0).orElse(null);
				if (hallOnly == null || !canAfford(settlement, hallOnly)
						|| (TownPlan.site(settlement, hallOnly, random, true, false) == null
						&& TownPlan.freeSite(settlement, hallOnly, STORE_SEARCH_REACH) == null)) {
					settlement.setStoreSoughtAtStreets(settlement.streets().size());
					return;
				}
				next = hallOnly;
			}
		} else if (TownPlan.site(settlement, next, random) == null) {
			// No room. The settlement lives on as it is: people keep to their work, and with
			// no new beds there are no more of them.
			settlement.setOutOfRoom(true);
			return;
		}
		settlement.setOutOfRoom(false);
		reserve(settlement, next);
	}

	/** Turns a storehouse into its next, bigger design. The lot and the ground work stay as they are. */
	private static void enlarge(Building store, Blueprint next, BlueprintLibrary library) {
		Blueprint current = library.get(store.blueprint()).orElseThrow();
		int footprint = current.width() * current.depth();
		int firstBlockStep = store.totalSteps() - (ConstructionPlan.count(current, 0) - footprint);
		int totalSteps = firstBlockStep + ConstructionPlan.count(next, 0) - next.width() * next.depth();
		store.enlarge(next.id(), totalSteps, firstBlockStep, next.cost(Commodity.WOOD), next.cost(Commodity.STONE));
	}

	private static void startWallPiece(Settlement settlement, BlueprintLibrary library, int keepWood, int keepStone) {
		Blueprint next = library.get(settlement.wallPlan().getFirst().blueprint()).orElse(null);
		Ledger ledger = settlement.ledger();
		if (next == null || ledger.available(Commodity.WOOD) < next.cost(Commodity.WOOD) + keepWood
				|| ledger.available(Commodity.STONE) < next.cost(Commodity.STONE) + keepStone) {
			return;
		}
		WallPiece piece = settlement.takeNextWallPiece();
		settlement.addBuilding(next, piece.x(), piece.z(), piece.rotation(), piece.sizeX(), piece.sizeZ(), -1, 0, 0,
				ConstructionPlan.count(next, 0));
		reserve(settlement, next);
	}

	private static boolean canAfford(Settlement settlement, Blueprint blueprint) {
		Ledger ledger = settlement.ledger();
		return ledger.available(Commodity.WOOD) >= blueprint.cost(Commodity.WOOD)
				&& ledger.available(Commodity.STONE) >= blueprint.cost(Commodity.STONE);
	}

	private static void reserve(Settlement settlement, Blueprint blueprint) {
		Ledger ledger = settlement.ledger();
		if (blueprint.cost(Commodity.WOOD) > 0) {
			ledger.reserve(Commodity.WOOD, blueprint.cost(Commodity.WOOD));
		}
		if (blueprint.cost(Commodity.STONE) > 0) {
			ledger.reserve(Commodity.STONE, blueprint.cost(Commodity.STONE));
		}
	}

	public static Status status(Settlement settlement, BlueprintLibrary library) {
		if (settlement.activeBuilding() != null) {
			return Status.BUILDING;
		}
		// What the settlement is waiting to start says more than a piece of wall going up meanwhile.
		Blueprint next = cheapestWanted(settlement, library);
		if (next == null) {
			return settlement.activeWallPiece() != null ? Status.BUILDING : Status.NOTHING_TO_BUILD;
		}
		if (settlement.isOutOfRoom()) {
			return Status.NO_ROOM;
		}
		return settlement.ledger().available(Commodity.WOOD) >= next.cost(Commodity.WOOD)
				&& settlement.ledger().available(Commodity.STONE) >= next.cost(Commodity.STONE)
				? Status.PLANNING : Status.WAITING_FOR_MATERIALS;
	}

	/**
	 * How much of a material the next thing the settlement wants would take, or 0 if it wants
	 * nothing or is already building (a project in hand has its materials reserved).
	 */
	public static int materialNeed(Settlement settlement, BlueprintLibrary library, Commodity commodity) {
		Blueprint next = cheapestWanted(settlement, library);
		return next == null ? 0 : next.cost(commodity);
	}

	/** Id of the design the settlement is saving up for, or an empty string. */
	public static String wantedBlueprint(Settlement settlement, BlueprintLibrary library) {
		Blueprint next = cheapestWanted(settlement, library);
		return next == null ? "" : next.id();
	}

	private static Blueprint cheapestWanted(Settlement settlement, BlueprintLibrary library) {
		if (settlement.activeBuilding() != null) {
			return null;
		}
		Choice choice = want(settlement, library);
		if (choice == null) {
			return null;
		}
		if (choice.design() != null) {
			return choice.design();
		}
		Blueprint cheapest = null;
		for (Blueprint option : ordinary(library, choice.category())) {
			if (cheapest == null || option.cost(Commodity.WOOD) + option.cost(Commodity.STONE)
					< cheapest.cost(Commodity.WOOD) + cheapest.cost(Commodity.STONE)) {
				cheapest = option;
			}
		}
		return cheapest;
	}

	/**
	 * What to build next.
	 *
	 * @param design  the one design that will do, or null for any of the category
	 * @param enlarge the standing building to turn into {@code design} (or the planned lot to begin on), or null
	 *                to build afresh
	 */
	private record Choice(Blueprint.Category category, Blueprint design, Building enlarge) {
		Choice(Blueprint.Category category) {
			this(category, null, null);
		}
	}

	/** What the settlement needs most, or null if it wants for nothing. */
	private static Choice want(Settlement settlement, BlueprintLibrary library) {
		if (!has(settlement, library, Blueprint.Category.SHELTER)) {
			return new Choice(Blueprint.Category.SHELTER);
		}
		Blueprint hall = library.get(Blueprint.MAIN_STORE + 1).orElse(null);
		Building store = settlement.mainStore();
		boolean planned = store != null && store.stage() == Building.Stage.PLANNED && hall != null;
		// A lot for the main storehouse is only looked for again once the streets have changed.
		boolean seekStore = hall != null && settlement.mainStore() == null
				&& settlement.storeSoughtAtStreets() != settlement.streets().size();
		if (!has(settlement, library, Blueprint.Category.STORAGE)) {
			// The main storehouse, on the lot set aside for it or wherever it fits; a plain one otherwise.
			if (planned) {
				return new Choice(Blueprint.Category.STORAGE, hall, store);
			}
			return seekStore ? new Choice(Blueprint.Category.STORAGE, hall, null) : new Choice(Blueprint.Category.STORAGE);
		}
		// Any roof counts towards a bed for everyone, the first hut too.
		if (allBeds(settlement) < settlement.size()) {
			return new Choice(Blueprint.Category.HOUSING);
		}
		if (planned) {
			return new Choice(Blueprint.Category.STORAGE, hall, store);
		}
		if (seekStore) {
			return new Choice(Blueprint.Category.STORAGE, hall, null);
		}
		// The storehouse grows with the settlement: a hall for a camp, wings for a settlement,
		// the whole of it for a village.
		if (store != null && store.stage() == Building.Stage.COMPLETE) {
			int tier = store.blueprint().charAt(store.blueprint().length() - 1) - '0';
			Blueprint bigger = tier < Math.min(MAIN_STORE_TIERS, settlement.stage().ordinal() + 1)
					? library.get(Blueprint.MAIN_STORE + (tier + 1)).orElse(null) : null;
			// The hall built by itself for want of room has no lot to grow on.
			Blueprint current = library.get(store.blueprint()).orElse(null);
			if (bigger != null && current != null && bigger.width() == current.width() && bigger.depth() == current.depth()) {
				return new Choice(Blueprint.Category.STORAGE, bigger, store);
			}
		}
		// Trades come with size: a settlement builds itself a smithy, a village a mason's yard.
		// It is built as soon as it can be paid for, and until then does not hold up the houses.
		Blueprint workshop = wantedWorkshop(settlement, library);
		if (workshop != null && canAfford(settlement, workshop)) {
			return new Choice(Blueprint.Category.WORKSHOP, workshop, null);
		}
		// Beds beyond one each are room to grow, and are only built by a settlement that is
		// well fed and has not outgrown its stage. Full stores are for building with: a rich
		// settlement builds further ahead of itself.
		boolean thriving = !settlement.isStarving() && settlement.foodDays() >= Population.COMFORTABLE_FOOD_DAYS;
		Ledger ledger = settlement.ledger();
		boolean rich = ledger.available(Commodity.WOOD) >= RICH_WOOD && ledger.available(Commodity.STONE) >= RICH_STONE;
		int room = settlement.growthRoom() * (rich ? 2 : 1);
		if (thriving && settlement.plannedBeds() < Math.min(settlement.size() + room, Population.softCap(settlement.stage()))) {
			return new Choice(Blueprint.Category.HOUSING);
		}
		return null;
	}

	/** The workshops, in the order they are built, and the stage a settlement has to have reached for each. */
	private static final String[] WORKSHOPS = {Blueprint.SMITHY, Blueprint.MASONS_YARD, Blueprint.MARKET};
	private static final SettlementStage[] WORKSHOP_STAGES = {SettlementStage.SETTLEMENT, SettlementStage.VILLAGE, SettlementStage.VILLAGE};

	/** The next workshop the settlement is big enough for and does not have yet (standing, planned or being built). */
	private static Blueprint wantedWorkshop(Settlement settlement, BlueprintLibrary library) {
		for (int i = 0; i < WORKSHOPS.length; i++) {
			if (settlement.stage().ordinal() < WORKSHOP_STAGES[i].ordinal()) {
				continue;
			}
			String design = WORKSHOPS[i];
			boolean has = false;
			for (Building building : settlement.buildings()) {
				has |= building.blueprint().equals(design);
			}
			if (!has && settlement.workshopSoughtAtStreets(i) != settlement.streets().size()) {
				return library.get(design).orElse(null);
			}
		}
		return null;
	}

	/** Designs of a category that are picked freely: the main storehouse is never one of them. */
	private static List<Blueprint> ordinary(BlueprintLibrary library, Blueprint.Category category) {
		List<Blueprint> options = new java.util.ArrayList<>(library.ofCategory(category));
		options.removeIf(option -> option.id().startsWith(Blueprint.MAIN_STORE));
		return options;
	}

	private static int allBeds(Settlement settlement) {
		int beds = 0;
		for (Building building : settlement.buildings()) {
			beds += building.beds();
		}
		return beds;
	}

	/**
	 * Whether the settlement has a building of this kind whose design still exists. One built
	 * from a design that was since withdrawn stays standing, but no longer counts as meeting the
	 * need: its replacement gets built.
	 */
	private static boolean has(Settlement settlement, BlueprintLibrary library, Blueprint.Category category) {
		for (Building building : settlement.buildings()) {
			if (building.category() == category && building.stage() != Building.Stage.PLANNED
					&& library.get(building.blueprint()).isPresent()) {
				return true;
			}
		}
		return false;
	}

	/** Any design of the kind that is wanted: variety is the point of having several. */
	private static Blueprint pick(List<Blueprint> options, IdGenerator random) {
		return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
	}

	private static void complete(Settlement settlement, Building building) {
		Ledger ledger = settlement.ledger();
		if (building.costWood() > 0) {
			ledger.consumeReserved(Commodity.WOOD, building.costWood());
		}
		if (building.costStone() > 0) {
			ledger.consumeReserved(Commodity.STONE, building.costStone());
		}
		building.setStage(Building.Stage.COMPLETE);
	}
}
