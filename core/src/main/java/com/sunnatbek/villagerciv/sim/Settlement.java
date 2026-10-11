package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Street;
import com.sunnatbek.villagerciv.build.WallPiece;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Settlement record: the economic and physical unit. It owns its citizens, its stockpile, its
 * territory and what the site survey learned about the land.
 */
public final class Settlement implements SimRecord<SettlementId>, CitizenGroup {
	public static final long NOT_ELIGIBLE = -1;

	private final SettlementId id;
	private final UUID founder;
	private final long foundedTick;
	private final int anchorX;
	private final int anchorY;
	private final int anchorZ;
	private final Map<CitizenId, Citizen> members = new LinkedHashMap<>();
	private final SortedSet<Long> territory = new TreeSet<>();
	private final Ledger ledger = new Ledger(this::markDirty);
	private final Map<UUID, Integer> contributions = new LinkedHashMap<>();
	private final List<WorkSite> sites = new ArrayList<>();
	private final int[] tools = new int[ToolKind.values().length];
	private final List<Street> streets = new ArrayList<>();
	private final List<Building> buildings = new ArrayList<>();
	private final List<WallPiece> wallPlan = new ArrayList<>();
	private int wallPieces;
	private int threat;
	private long lastThreatTick;
	private final List<Request> requests = new ArrayList<>();
	private final Map<UUID, Integer> reputation = new LinkedHashMap<>();
	private final StockStatus[] status = new StockStatus[Commodity.values().length];
	private final long[] statusSince = new long[Commodity.values().length];
	private final long[] askAgain = new long[Commodity.values().length];
	private final EnumSet<Policy> plans = EnumSet.noneOf(Policy.class);
	private int morale = 50;
	private long promotionSinceTick = NOT_ELIGIBLE;
	private long lastPopulationTick;
	private int hunger;
	private long lastUpkeepTick;
	private boolean starving;
	private String name;
	private SettlementStage stage = SettlementStage.FOUNDING_CAMP;
	private SiteScore siteScore = SiteScore.UNKNOWN;
	private PlotMap plots = PlotMap.EMPTY;
	private boolean dirty;

	public Settlement(SettlementId id, String name, UUID founder, long foundedTick, int anchorX, int anchorY, int anchorZ) {
		this.id = id;
		this.name = name;
		this.founder = founder;
		this.foundedTick = foundedTick;
		this.lastUpkeepTick = foundedTick;
		this.lastPopulationTick = foundedTick;
		this.anchorX = anchorX;
		this.anchorY = anchorY;
		this.anchorZ = anchorZ;
	}

	@Override
	public SettlementId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
		dirty = true;
	}

	/** The founding patron: the player who led the founders here. */
	public UUID founder() {
		return founder;
	}

	@Override
	public UUID player() {
		return founder;
	}

	public long foundedTick() {
		return foundedTick;
	}

	/** Position of the Town Center, the settlement's physical heart. */
	public int anchorX() {
		return anchorX;
	}

	public int anchorY() {
		return anchorY;
	}

	public int anchorZ() {
		return anchorZ;
	}

	public SettlementStage stage() {
		return stage;
	}

	public void setStage(SettlementStage stage) {
		if (this.stage != stage) {
			this.stage = stage;
			dirty = true;
		}
	}

	public Ledger ledger() {
		return ledger;
	}

	public List<Street> streets() {
		return Collections.unmodifiableList(streets);
	}

	public void addStreet(Street street) {
		streets.add(street);
		dirty = true;
	}

	public List<Building> buildings() {
		return Collections.unmodifiableList(buildings);
	}

	public Building building(int id) {
		for (int i = 0; i < buildings.size(); i++) {
			if (buildings.get(i).id() == id) {
				return buildings.get(i);
			}
		}
		return null;
	}

	/** Starts a new building on a lot. */
	public Building addBuilding(Blueprint blueprint, int x, int z, int rotation, int sizeX, int sizeZ, int street,
			int paveFrom, int paveTo, int totalSteps) {
		int id = 1;
		for (Building building : buildings) {
			id = Math.max(id, building.id() + 1);
		}
		Building building = new Building(id, blueprint.id(), blueprint.category(), x, z, rotation, sizeX, sizeZ, street,
				paveFrom, paveTo, totalSteps, blueprint.beds(), blueprint.cost(Commodity.WOOD), blueprint.cost(Commodity.STONE),
				this::markDirty);
		buildings.add(building);
		dirty = true;
		return building;
	}

	/** Adds a building read from disk. */
	public void restoreBuilding(Building building) {
		buildings.add(building);
	}

	/** The building under construction, if any: a settlement builds one thing at a time. */
	public Building activeBuilding() {
		return underConstruction(false);
	}

	/**
	 * The piece of wall being raised, or null. The wall is a project of its own beside the
	 * ordinary one: its builders work at the edge of the settlement while houses go up inside.
	 */
	public Building activeWallPiece() {
		return underConstruction(true);
	}

	private Building underConstruction(boolean defence) {
		for (int i = 0; i < buildings.size(); i++) {
			Building building = buildings.get(i);
			if (building.stage() == Building.Stage.UNDER_CONSTRUCTION && (building.category() == Blueprint.Category.DEFENSE) == defence) {
				return building;
			}
		}
		return null;
	}

	/** Everything being built right now: at most one ordinary building and one piece of wall. */
	public List<Building> activeBuildings() {
		List<Building> active = new ArrayList<>(2);
		Building civil = activeBuilding();
		if (civil != null) {
			active.add(civil);
		}
		Building wall = activeWallPiece();
		if (wall != null) {
			active.add(wall);
		}
		return active;
	}

	/** The finished workshop of a design, or null if the settlement has none standing. */
	public Building workshop(String design) {
		for (int i = 0; i < buildings.size(); i++) {
			Building building = buildings.get(i);
			if (building.blueprint().equals(design) && building.stage() == Building.Stage.COMPLETE) {
				return building;
			}
		}
		return null;
	}

	/** How many streets there were when room for a workshop was last looked for in vain; -1 if never. Not saved. */
	public int workshopSoughtAtStreets(int workshop) {
		return workshopSoughtAtStreets[workshop];
	}

	public void setWorkshopSoughtAtStreets(int workshop, int streets) {
		workshopSoughtAtStreets[workshop] = streets;
	}

	/** Since when the settlement has been too small for its stage, or {@link #NOT_ELIGIBLE}. Not saved. */
	public long declineSinceTick() {
		return declineSinceTick;
	}

	public void setDeclineSinceTick(long tick) {
		declineSinceTick = tick;
	}

	/** Whether anyone holds this job right now. */
	public boolean hasWorker(JobType job) {
		for (Citizen citizen : members.values()) {
			if (citizen.job() == job) {
				return true;
			}
		}
		return false;
	}

	/** The main storehouse, or null while the settlement has none. */
	private int storeSoughtAtStreets = -1;
	private boolean groundWork;
	private boolean lampWork;
	private final int[] workshopSoughtAtStreets = {-1, -1, -1};
	private long declineSinceTick = NOT_ELIGIBLE;
	private boolean outOfRoom;
	private static final int WALL_INSET = 5;
	private int wallBoundsKey = -1;
	private int[] wallBounds;

	/** Whether there are holes in the settlement's ground waiting to be filled. Told by whoever can see the ground; not saved. */
	public boolean hasGroundWork() {
		return groundWork;
	}

	public void setGroundWork(boolean groundWork) {
		this.groundWork = groundWork;
	}

	/** Whether there are dark corners in the settlement waiting for a lamp. Told like {@link #hasGroundWork}; not saved. */
	public boolean hasLampWork() {
		return lampWork;
	}

	public void setLampWork(boolean lampWork) {
		this.lampWork = lampWork;
	}


	public Building mainStore() {
		for (int i = 0; i < buildings.size(); i++) {
			if (buildings.get(i).blueprint().startsWith(Blueprint.MAIN_STORE)) {
				return buildings.get(i);
			}
		}
		return null;
	}

	/** How many streets there were when a lot for the main storehouse was last looked for in vain. Not saved. */
	public int storeSoughtAtStreets() {
		return storeSoughtAtStreets;
	}

	public void setStoreSoughtAtStreets(int streets) {
		storeSoughtAtStreets = streets;
	}

	public boolean hasBuilding(Blueprint.Category category) {
		for (int i = 0; i < buildings.size(); i++) {
			if (buildings.get(i).category() == category && buildings.get(i).stage() != Building.Stage.PLANNED) {
				return true;
			}
		}
		return false;
	}

	/** Beds in houses, including houses still being built: what has been provided for. */
	public int plannedBeds() {
		int beds = 0;
		for (Building building : buildings) {
			if (building.category() == Blueprint.Category.HOUSING) {
				beds += building.beds();
			}
		}
		return beds;
	}

	/** Beds people can sleep in tonight: every finished building that has any. */
	public int housingCapacity() {
		int beds = 0;
		for (Building building : buildings) {
			if (building.stage() != Building.Stage.UNDER_CONSTRUCTION && building.stage() != Building.Stage.PLANNED) {
				beds += building.beds();
			}
		}
		return beds;
	}

	/** Tools of one kind in the settlement's store. */
	public int tools(ToolKind kind) {
		return tools[kind.ordinal()];
	}

	/**
	 * A player hands over tools. They are kept by kind, because a hoe is no use to a miner; the
	 * ledger's TOOLS line stays the total of all kinds.
	 */
	public void contributeTools(UUID player, ToolKind kind, int count) {
		tools[kind.ordinal()] += count;
		contribute(player, Commodity.TOOLS, count);
	}

	/** Tools the settlement made for itself: into the store, with nobody to thank for them. */
	public void makeTools(ToolKind kind, int count) {
		tools[kind.ordinal()] += count;
		ledger.add(Commodity.TOOLS, count);
		dirty = true;
	}

	/** Restores a saved tool count, without touching the ledger. */
	public void restoreTools(ToolKind kind, int count) {
		tools[kind.ordinal()] = Math.max(0, count);
	}

	public List<WorkSite> sites() {
		return Collections.unmodifiableList(sites);
	}

	public WorkSite site(int id) {
		for (int i = 0; i < sites.size(); i++) {
			if (sites.get(i).id() == id) {
				return sites.get(i);
			}
		}
		return null;
	}

	/** Opens a new work site centred on a column. */
	public WorkSite addSite(JobType type, int x, int z) {
		int id = 1;
		for (WorkSite site : sites) {
			id = Math.max(id, site.id() + 1);
		}
		return restoreSite(id, type, x, z);
	}

	/** Adds a site with a known id, as when loading. */
	public WorkSite restoreSite(int id, JobType type, int x, int z) {
		// Away from the Town Center along the longer axis.
		int dx = x - anchorX;
		int dz = z - anchorZ;
		boolean alongX = Math.abs(dx) >= Math.abs(dz);
		WorkSite site = new WorkSite(id, type, x, z, alongX ? (dx >= 0 ? 1 : -1) : 0, alongX ? 0 : (dz >= 0 ? 1 : -1), this::markDirty);
		sites.add(site);
		dirty = true;
		return site;
	}

	/**
	 * How much the settlement has suffered lately: it rises with every attack on a citizen and
	 * fades day by day when the nights are quiet.
	 */
	public int threat() {
		return threat;
	}

	public void setThreat(int threat) {
		threat = Math.max(0, threat);
		if (this.threat != threat) {
			this.threat = threat;
			dirty = true;
		}
	}

	public long lastThreatTick() {
		return lastThreatTick;
	}

	public void setLastThreatTick(long lastThreatTick) {
		this.lastThreatTick = lastThreatTick;
		dirty = true;
	}

	/** Pieces of the planned wall that have not been started yet, in building order. */
	public List<WallPiece> wallPlan() {
		return Collections.unmodifiableList(wallPlan);
	}

	/**
	 * Whether a block lies within the settlement's bounds. Once a wall is planned, that is the
	 * ground inside it: a settlement does not spill out past its own wall. Without a wall there
	 * are no bounds.
	 */
	public boolean withinWalls(int x, int z) {
		int[] bounds = wallBounds();
		return bounds == null || (x >= bounds[0] && x <= bounds[2] && z >= bounds[1] && z <= bounds[3]);
	}

	/** {west, north, east, south} of the ground inside the wall, or null if there is no wall. */
	private int[] wallBounds() {
		int key = buildings.size() * 1_000 + wallPlan.size();
		if (key != wallBoundsKey) {
			wallBoundsKey = key;
			int west = Integer.MAX_VALUE;
			int north = Integer.MAX_VALUE;
			int east = Integer.MIN_VALUE;
			int south = Integer.MIN_VALUE;
			for (Building building : buildings) {
				if (building.category() == Blueprint.Category.DEFENSE) {
					west = Math.min(west, building.x());
					north = Math.min(north, building.z());
					east = Math.max(east, building.x() + building.sizeX() - 1);
					south = Math.max(south, building.z() + building.sizeZ() - 1);
				}
			}
			for (WallPiece piece : wallPlan) {
				west = Math.min(west, piece.x());
				north = Math.min(north, piece.z());
				east = Math.max(east, piece.x() + piece.sizeX() - 1);
				south = Math.max(south, piece.z() + piece.sizeZ() - 1);
			}
			// The corner towers stand out past the wall line; inside starts a few steps in from that.
			wallBounds = west == Integer.MAX_VALUE ? null
					: new int[] {west + WALL_INSET, north + WALL_INSET, east - WALL_INSET, south - WALL_INSET};
		}
		return wallBounds;
	}

	/** Not saved: whether the last search for a building lot found none. */
	public boolean isOutOfRoom() {
		return outOfRoom;
	}

	public void setOutOfRoom(boolean outOfRoom) {
		this.outOfRoom = outOfRoom;
	}

	/** How many pieces the wall has in all, or 0 if no wall has been planned. */
	public int wallPieces() {
		return wallPieces;
	}

	public void setWallPlan(List<WallPiece> pieces, int total) {
		wallPlan.clear();
		wallPlan.addAll(pieces);
		wallPieces = total;
		dirty = true;
	}

	public WallPiece takeNextWallPiece() {
		dirty = true;
		return wallPlan.remove(0);
	}

	public int guards() {
		int guards = 0;
		for (Citizen citizen : members.values()) {
			if (citizen.isGuard()) {
				guards++;
			}
		}
		return guards;
	}

	/** The Development Plans the settlement follows, in the order of the {@link Policy} enum. */
	public Set<Policy> plans() {
		return Collections.unmodifiableSet(plans);
	}

	public boolean hasPlan(Policy plan) {
		return plans.contains(plan);
	}

	/** Without any plan a settlement lives and works, but stays what it is. */
	public boolean hasAnyPlan() {
		return !plans.isEmpty();
	}

	/**
	 * Takes up a plan alongside those already followed.
	 *
	 * @return false if the settlement follows it already
	 */
	public boolean adoptPlan(Policy plan) {
		if (plan == Policy.NONE || !plans.add(plan)) {
			return false;
		}
		dirty = true;
		return true;
	}

	/** Extra work speed, in percent, the plans give a job: each plan adds its own. */
	public int workBonusPercent(JobType job) {
		int bonus = 0;
		for (Policy plan : plans) {
			bonus += plan.workBonusPercent(job);
		}
		return bonus;
	}

	/**
	 * How readily children are born, in percent of normal. The best of the plans counts: one
	 * that holds births back does so only where no other plan makes up for it.
	 */
	public int birthPercent() {
		int best = plans.isEmpty() ? Policy.NONE.birthPercent() : 0;
		for (Policy plan : plans) {
			best = Math.max(best, plan.birthPercent());
		}
		return best;
	}

	/** Spare beds kept ahead of the population; again the most generous plan counts. */
	public int growthRoom() {
		int best = plans.isEmpty() ? Policy.NONE.growthRoom() : 0;
		for (Policy plan : plans) {
			best = Math.max(best, plan.growthRoom());
		}
		return best;
	}

	public StockStatus status(Commodity commodity) {
		StockStatus current = status[commodity.ordinal()];
		return current == null ? StockStatus.OK : current;
	}

	/** Since when the commodity has had its present status. */
	public long statusSinceTick(Commodity commodity) {
		return statusSince[commodity.ordinal()];
	}

	public void setStatus(Commodity commodity, StockStatus newStatus, long gameTick) {
		if (status(commodity) != newStatus) {
			status[commodity.ordinal()] = newStatus;
			statusSince[commodity.ordinal()] = gameTick;
			dirty = true;
		}
	}

	/** Restores a saved status together with the time it began. */
	public void restoreStatus(Commodity commodity, StockStatus savedStatus, long sinceTick, long askAgainTick) {
		status[commodity.ordinal()] = savedStatus;
		statusSince[commodity.ordinal()] = sinceTick;
		askAgain[commodity.ordinal()] = askAgainTick;
	}

	/** Open requests to the patron. */
	public List<Request> requests() {
		return Collections.unmodifiableList(requests);
	}

	public Request requestFor(Commodity commodity) {
		for (int i = 0; i < requests.size(); i++) {
			if (requests.get(i).commodity() == commodity) {
				return requests.get(i);
			}
		}
		return null;
	}

	/** The earliest tick at which this commodity may be asked for again. */
	public long askAgainTick(Commodity commodity) {
		return askAgain[commodity.ordinal()];
	}

	public Request openRequest(Commodity commodity, int amount, String purpose, long gameTick, long deadlineTick) {
		int id = 1;
		for (Request request : requests) {
			id = Math.max(id, request.id() + 1);
		}
		Request request = new Request(id, commodity, amount, 0, purpose, gameTick, deadlineTick);
		requests.add(request);
		dirty = true;
		return request;
	}

	public void restoreRequest(Request request) {
		requests.add(request);
	}

	Request deliverTo(int requestId, int units) {
		for (int i = 0; i < requests.size(); i++) {
			Request request = requests.get(i);
			if (request.id() == requestId) {
				Request updated = request.withDelivered(request.delivered() + units);
				requests.set(i, updated);
				dirty = true;
				return updated;
			}
		}
		throw new IllegalArgumentException("no request " + requestId);
	}

	void closeRequest(int requestId, long askAgainTick) {
		for (int i = 0; i < requests.size(); i++) {
			if (requests.get(i).id() == requestId) {
				askAgain[requests.get(i).commodity().ordinal()] = askAgainTick;
				requests.remove(i);
				dirty = true;
				return;
			}
		}
	}

	/** A player's standing with this settlement, in whole points. */
	public int reputationOf(UUID player) {
		return reputation.getOrDefault(player, 0) / 10;
	}

	/** Standing as stored: tenths of a point per player. */
	public Map<UUID, Integer> reputationTenths() {
		return Collections.unmodifiableMap(reputation);
	}

	public void addReputation(UUID player, int tenths) {
		if (tenths != 0) {
			reputation.merge(player, tenths, Integer::sum);
			dirty = true;
		}
	}

	/** 0–100: how the settlement as a whole is faring. 50 is "getting by". */
	public int morale() {
		return morale;
	}

	public void setMorale(int morale) {
		morale = Math.clamp(morale, 0, 100);
		if (this.morale != morale) {
			this.morale = morale;
			dirty = true;
		}
	}

	/** Since when the settlement has met everything its next stage asks, or {@link #NOT_ELIGIBLE}. */
	public long promotionSinceTick() {
		return promotionSinceTick;
	}

	public void setPromotionSinceTick(long promotionSinceTick) {
		if (this.promotionSinceTick != promotionSinceTick) {
			this.promotionSinceTick = promotionSinceTick;
			dirty = true;
		}
	}

	public long lastPopulationTick() {
		return lastPopulationTick;
	}

	public void setLastPopulationTick(long lastPopulationTick) {
		this.lastPopulationTick = lastPopulationTick;
		dirty = true;
	}

	public int children() {
		int children = 0;
		for (Citizen citizen : members.values()) {
			if (citizen.isChild()) {
				children++;
			}
		}
		return children;
	}

	/** Days the food in store would last at the present number of mouths. */
	public int foodDays() {
		int mouthsDoubled = 2 * size() - children();
		return mouthsDoubled == 0 ? 0 : ledger.onHand(Commodity.FOOD) * 2 / mouthsDoubled;
	}

	/** Twentieths of a meal owed; see {@link SettlementUpkeep}. */
	public int hunger() {
		return hunger;
	}

	public void setHunger(int hunger) {
		this.hunger = hunger;
		dirty = true;
	}

	public long lastUpkeepTick() {
		return lastUpkeepTick;
	}

	public void setLastUpkeepTick(long lastUpkeepTick) {
		this.lastUpkeepTick = lastUpkeepTick;
		dirty = true;
	}

	/** True while there is not enough food for everyone. */
	@Override
	public boolean isStarving() {
		return starving;
	}

	public void setStarving(boolean starving) {
		if (this.starving != starving) {
			this.starving = starving;
			dirty = true;
		}
	}

	/** Units each player has given to this settlement so far, whatever the commodity. */
	public Map<UUID, Integer> contributions() {
		return Collections.unmodifiableMap(contributions);
	}

	public int contributionOf(UUID player) {
		return contributions.getOrDefault(player, 0);
	}

	/** A player hands over goods: they enter the stockpile and are remembered as that player's gift. */
	public void contribute(UUID player, Commodity commodity, int units) {
		ledger.add(commodity, units);
		contributions.merge(player, units, Integer::sum);
	}

	/** Restores a saved contribution total. */
	public void restoreContribution(UUID player, int units) {
		contributions.put(player, units);
	}

	/** Claimed chunks, as {@link ChunkKey}s. */
	public SortedSet<Long> territory() {
		return Collections.unmodifiableSortedSet(territory);
	}

	public void claim(long chunkKey) {
		if (territory.add(chunkKey)) {
			dirty = true;
		}
	}

	public SiteScore siteScore() {
		return siteScore;
	}

	/** Buildable ground around the Town Center, as found by the site survey. */
	public PlotMap plots() {
		return plots;
	}

	public void setSurvey(SiteScore siteScore, PlotMap plots) {
		this.siteScore = siteScore;
		this.plots = plots;
		dirty = true;
	}

	@Override
	public Collection<Citizen> members() {
		return Collections.unmodifiableCollection(members.values());
	}

	@Override
	public Optional<Citizen> member(CitizenId id) {
		return Optional.ofNullable(members.get(id));
	}

	@Override
	public int size() {
		return members.size();
	}

	public void add(Citizen citizen) {
		citizen.attach(this);
		members.put(citizen.id(), citizen);
		dirty = true;
	}

	public Optional<Citizen> remove(CitizenId id) {
		Citizen citizen = members.remove(id);
		if (citizen != null) {
			dirty = true;
		}
		return Optional.ofNullable(citizen);
	}

	@Override
	public boolean isDirty() {
		return dirty;
	}

	@Override
	public void markDirty() {
		dirty = true;
	}

	@Override
	public void markClean() {
		dirty = false;
	}

	/** Square grid of buildable cells centred on the Town Center. Immutable. */
	public record PlotMap(int originX, int originZ, int size, BitSet buildable) {
		public static final PlotMap EMPTY = new PlotMap(0, 0, 0, new BitSet());

		public boolean isBuildable(int blockX, int blockZ) {
			int dx = blockX - originX;
			int dz = blockZ - originZ;
			return dx >= 0 && dz >= 0 && dx < size && dz < size && buildable.get(dz * size + dx);
		}

		public int buildableCount() {
			return buildable.cardinality();
		}
	}
}
