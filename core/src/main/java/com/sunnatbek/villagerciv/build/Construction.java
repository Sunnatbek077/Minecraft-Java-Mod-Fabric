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
		// One piece of wall for each crew the settlement has; a crew that is free takes the next.
		for (int crew = settlement.activeWallPieces(); crew < settlement.wallCrews() && !settlement.wallPlan().isEmpty(); crew++) {
			int planned = settlement.wallPlan().size();
			// The wall takes what is left over: materials the next building is waiting for are
			// not spent on it, or a costly building would never get its turn.
			Blueprint waiting = cheapestWanted(settlement, library);
			startWallPiece(settlement, library, waiting == null ? 0 : waiting.cost(Commodity.WOOD),
					waiting == null ? 0 : waiting.cost(Commodity.STONE));
			if (settlement.wallPlan().size() == planned) {
				break; // it could not be afforded: the others would fare no better
			}
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
		Blueprint next = choice.design();
		List<Blueprint> others = List.of();
		if (next == null) {
			// Any design of the kind that can be paid for today: a young settlement builds
			// plain houses, and the fine ones come when there is the wood and stone for them.
			List<Blueprint> affordable = new java.util.ArrayList<>(ordinary(library, choice.category()));
			affordable.removeIf(option -> !canAfford(settlement, option) || option.minStage() > settlement.stage().ordinal());
			next = pickByBeds(leastBuilt(settlement, affordable), random);
			others = affordable;
		}
		if (next == null || !canAfford(settlement, next)) {
			return; // wait for the lumberjacks and miners (or a generous player)
		}
		if (choice.category() == Blueprint.Category.WORKSHOP || choice.category() == Blueprint.Category.PUBLIC) {
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
		} else if (settlement.foundNoRoomLately()) {
			return; // looked a moment ago, and nothing has changed since
		} else if (TownPlan.site(settlement, next, random) == null) {
			// No lot for that one. Before giving up, the smaller designs are tried, the smallest
			// first: the last corners of a full settlement still take a narrow house.
			next = smallerThatFits(settlement, others, next, random);
			if (next == null) {
				// No room. The settlement lives on as it is: people keep to their work, and with
				// no new beds there are no more of them.
				settlement.noteNoRoom();
				return;
			}
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
			return new Choice(workshop.category(), workshop, null);
		}
		// Beds beyond one each are room to grow, and are only built by a settlement that is
		// well fed and has not outgrown its stage. Full stores are for building with: a rich
		// settlement builds further ahead of itself.
		boolean thriving = !settlement.isStarving() && settlement.foodDays() >= Population.COMFORTABLE_FOOD_DAYS;
		Ledger ledger = settlement.ledger();
		boolean rich = ledger.available(Commodity.WOOD) >= RICH_WOOD && ledger.available(Commodity.STONE) >= RICH_STONE;
		// And a big one further still: a town that built four beds ahead would have its builders
		// standing about most of the day. With stores piled high it builds for a fifth as many
		// people again as it has; more than that and the place is all houses, half of them empty.
		boolean overflowing = ledger.available(Commodity.WOOD) >= 10 * RICH_WOOD && ledger.available(Commodity.STONE) >= 10 * RICH_STONE;
		int room = Math.max(settlement.growthRoom() * (rich ? 2 : 1), overflowing ? settlement.size() / 5 : 0);
		if (thriving && settlement.plannedBeds() < Math.min(settlement.size() + room, Population.softCap(settlement.stage()))) {
			return new Choice(Blueprint.Category.HOUSING);
		}
		return null;
	}

	/**
	 * The buildings a settlement puts up once each, in the order it does so: its workshops, and
	 * after its patron has let it grow beyond its first wall, its public buildings. With each,
	 * the stage a settlement has to have reached, and whether it takes that leave to grow.
	 */
	private static final String[] WORKSHOPS = {Blueprint.SMITHY, Blueprint.MASONS_YARD, Blueprint.MARKET,
			"well", "tavern", "chapel", "fountain", "cattle_pen", "sheep_pen", "chicken_coop", "pig_sty", Blueprint.LIBRARY};
	private static final SettlementStage[] WORKSHOP_STAGES = {SettlementStage.SETTLEMENT, SettlementStage.VILLAGE, SettlementStage.VILLAGE,
			SettlementStage.TOWN, SettlementStage.TOWN, SettlementStage.TOWN, SettlementStage.TOWN,
			// The pens come with the land beyond the first wall, whatever the settlement is called by then.
			SettlementStage.VILLAGE, SettlementStage.VILLAGE, SettlementStage.VILLAGE, SettlementStage.VILLAGE,
			// A library is a town's; and unlike the rest, a town builds as many as its librarians need.
			SettlementStage.TOWN};
	private static final int FIRST_PUBLIC = 3;

	/** The next workshop the settlement is big enough for and does not have yet (standing, planned or being built). */
	private static Blueprint wantedWorkshop(Settlement settlement, BlueprintLibrary library) {
		for (int i = 0; i < WORKSHOPS.length; i++) {
			if (settlement.stage().ordinal() < WORKSHOP_STAGES[i].ordinal() || (i >= FIRST_PUBLIC && settlement.expansion() == 0)) {
				continue;
			}
			String design = WORKSHOPS[i];
			boolean has = false;
			int count = 0;
			for (Building building : settlement.buildings()) {
				has |= building.blueprint().equals(design);
				count += building.blueprint().equals(design) ? 1 : 0;
			}
			if (design.equals(Blueprint.LIBRARY)) {
				// Another library once the lecterns of those that stand are all taken, until there
				// is a lectern for every enchantment.
				int seats = count * Blueprint.LIBRARY_SEATS;
				int taken = 0;
				for (com.sunnatbek.villagerciv.sim.Citizen citizen : settlement.members()) {
					taken += citizen.job() == com.sunnatbek.villagerciv.sim.JobType.STUDY ? 1 : 0;
				}
				has = seats >= settlement.studies() || taken < seats;
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

	/**
	 * Another design to build where the chosen one found no lot. The ones built least often are
	 * tried first, so a tight corner is filled with something the settlement does not have yet
	 * rather than with one more of its smallest house; among those, the smaller first.
	 */
	private static Blueprint smallerThatFits(Settlement settlement, List<Blueprint> options, Blueprint tooBig, IdGenerator random) {
		java.util.Map<String, Integer> built = new java.util.HashMap<>();
		for (Building building : settlement.buildings()) {
			built.merge(building.blueprint(), 1, Integer::sum);
		}
		List<Blueprint> others = new java.util.ArrayList<>(options);
		others.removeIf(option -> option.width() >= tooBig.width() && option.depth() >= tooBig.depth());
		others.sort(java.util.Comparator.comparingInt((Blueprint option) -> built.getOrDefault(option.id(), 0))
				.thenComparingInt(option -> option.width() * option.depth()).thenComparing(Blueprint::id));
		for (Blueprint option : others) {
			if (TownPlan.site(settlement, option, random) != null) {
				return option;
			}
		}
		return null;
	}

	/**
	 * Of these designs, the ones the settlement has built least often: none at all, while there
	 * is such a one. So no house is built a second time until every design that can be afforded
	 * has been built once, and which of them comes next is left to chance.
	 */
	private static List<Blueprint> leastBuilt(Settlement settlement, List<Blueprint> options) {
		java.util.Map<String, Integer> built = new java.util.HashMap<>();
		for (Building building : settlement.buildings()) {
			built.merge(building.blueprint(), 1, Integer::sum);
		}
		int fewest = Integer.MAX_VALUE;
		for (Blueprint option : options) {
			fewest = Math.min(fewest, built.getOrDefault(option.id(), 0));
		}
		List<Blueprint> rarest = new java.util.ArrayList<>();
		for (Blueprint option : options) {
			if (built.getOrDefault(option.id(), 0) == fewest) {
				rarest.add(option);
			}
		}
		return rarest;
	}

	/**
	 * Any of these designs, by chance, but a house for six comes up three times as often as a
	 * house for two: land is what a settlement runs out of, and it should not spend its few
	 * lots mostly on the smallest houses.
	 */
	private static Blueprint pickByBeds(List<Blueprint> options, IdGenerator random) {
		int total = 0;
		for (Blueprint option : options) {
			total += Math.max(1, option.beds());
		}
		if (total == 0) {
			return null;
		}
		int drawn = random.nextInt(total);
		for (Blueprint option : options) {
			drawn -= Math.max(1, option.beds());
			if (drawn < 0) {
				return option;
			}
		}
		return options.getLast();
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
