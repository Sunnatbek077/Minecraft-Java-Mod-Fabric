package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.BlueprintLibrary;
import com.sunnatbek.villagerciv.build.Fortification;
import com.sunnatbek.villagerciv.build.WallPiece;

import com.sunnatbek.villagerciv.build.Building;

import java.util.ArrayList;
import java.util.List;

/**
 * The General's side of running a settlement: how dangerous things are, who stands guard, and
 * whether it is time for a wall.
 *
 * <p>Defence answers danger; it is not built on a schedule. A settlement nothing has attacked
 * posts a token watch and puts its wood into houses. One that keeps losing people to the night
 * raises a militia, and once the threat outgrows what guards can handle, a wall. A Military plan
 * makes it do so without waiting to be hurt first. Food always comes before fortification.
 */
public final class Defense {
	/** Threat added when a citizen is hurt by a monster, and when one is killed. */
	public static final int HURT = 1;
	public static final int KILLED = 6;
	/** Threat at which a settlement starts on a wall. */
	public static final int WALL_THREAT = 10;

	private static final long DAY = ExpeditionUpkeep.TICKS_PER_DAY;
	private static final int THREAT_PER_GUARD = 4;
	/** Places for guards on one watchtower. */
	public static final int GUARDS_PER_TOWER = 3;
	/** Ore that goes into one suit of armour: as much iron as a player's own would take. */
	public static final int ARMOR_ORE = 24;

	private Defense() {
	}

	/** A citizen of this settlement was attacked. */
	public static void incident(Settlement settlement, int weight) {
		settlement.setThreat(settlement.threat() + weight);
	}

	static void update(Settlement settlement, BlueprintLibrary library, long gameTick) {
		// Quiet days wear the sense of danger down by a third each.
		while (gameTick - settlement.lastThreatTick() >= DAY) {
			settlement.setLastThreatTick(settlement.lastThreatTick() == 0 ? gameTick : settlement.lastThreatTick() + DAY);
			settlement.setThreat(settlement.threat() * 2 / 3);
		}
		outgrowFirstWall(settlement, library);
		assignGuards(settlement);
		manPosts(settlement);
		armDefenders(settlement);

		boolean military = settlement.hasPlan(Policy.MILITARY);
		if (settlement.wallPieces() == 0 && settlement.stage().ordinal() >= SettlementStage.SETTLEMENT.ordinal()
				&& (settlement.threat() >= WALL_THREAT || military)
				&& !settlement.isStarving() && settlement.foodDays() >= Population.COMFORTABLE_FOOD_DAYS) {
			List<WallPiece> pieces = Fortification.plan(settlement, library);
			settlement.setWallPlan(pieces, pieces.size());
		}
	}

	/**
	 * What becomes of the first wall once a settlement has been let grow beyond it. The stone
	 * ring is given its full count of towers; and when it stands complete, the wooden ring inside
	 * it, which by then only stands in the way, is condemned. Its guards move out to the new wall
	 * by themselves: posts are only ever on what stands.
	 */
	private static void outgrowFirstWall(Settlement settlement, BlueprintLibrary library) {
		int newest = settlement.expansion();
		if (newest == 0 || library.get(Fortification.STONE_TOWER).isEmpty()) {
			return;
		}
		boolean newRing = false;
		boolean olderRings = false;
		boolean unfinished = !settlement.wallPlan().isEmpty();
		for (Building building : settlement.buildings()) {
			if (building.category() != Blueprint.Category.DEFENSE) {
				continue;
			}
			if (Fortification.ringOf(building.blueprint()) < newest) {
				olderRings |= building.stage() != Building.Stage.CONDEMNED;
			} else {
				newRing = true;
				unfinished |= building.stage() != Building.Stage.COMPLETE;
			}
		}
		for (WallPiece piece : settlement.wallPlan()) {
			newRing |= Fortification.ringOf(piece.blueprint()) >= newest;
		}
		if (!newRing) {
			return; // an enlarged settlement that never walled itself in
		}
		// A square stone ring planned when it had only its corner towers is given the rest.
		List<WallPiece> missing = new ArrayList<>();
		if (!settlement.hasShapedWall()) {
			for (WallPiece tower : Fortification.sideTowers(settlement, library)) {
				boolean there = false;
				for (Building building : settlement.buildings()) {
					there |= building.blueprint().equals(tower.blueprint()) && building.x() == tower.x() && building.z() == tower.z();
				}
				for (WallPiece piece : settlement.wallPlan()) {
					there |= piece.blueprint().equals(tower.blueprint()) && piece.x() == tower.x() && piece.z() == tower.z();
				}
				if (!there) {
					missing.add(tower);
				}
			}
		}
		if (!missing.isEmpty()) {
			List<WallPiece> plan = new ArrayList<>(missing);
			plan.addAll(settlement.wallPlan());
			settlement.setWallPlan(plan, settlement.wallPieces() + missing.size());
			return;
		}
		if (!unfinished && olderRings) {
			for (Building building : settlement.buildings()) {
				if (building.category() == Blueprint.Category.DEFENSE && Fortification.ringOf(building.blueprint()) < newest) {
					building.setStage(Building.Stage.CONDEMNED);
				}
			}
		}
	}

	/**
	 * Guards are posted on the wall: on the towers and above the gates. One goes to every tower
	 * first, then one to every gate, then a second and a third to every tower, for as long as
	 * there are guards to post. Any left over keep the old night watch on foot.
	 */
	private static void manPosts(Settlement settlement) {
		List<int[]> posts = posts(settlement);
		int watch = Math.min(posts.size(), fullWatch(settlement, posts));
		int patrols = patrolsWanted(settlement, watch);
		// The wall gets its full watch; a third guard goes up a tower only when there are more
		// guards than the watch and the patrols take.
		int onTheWall = Math.min(posts.size(), Math.max(watch, settlement.guards() - patrols));
		int next = 0;
		int walking = 0;
		for (Citizen citizen : settlement.members()) {
			citizen.setPatrol(Citizen.Patrol.NONE);
			if (citizen.isGuard() && next < onTheWall) {
				citizen.setPost(posts.get(next)[0], posts.get(next)[1]);
				next++;
			} else {
				citizen.setPost(Citizen.NO_POST, 0);
				// As many again walk the rounds inside the wall, swords and crossbows by turns.
				if (citizen.isGuard() && walking < patrols) {
					citizen.setPatrol(walking % 2 == 0 ? Citizen.Patrol.SWORD : Citizen.Patrol.CROSSBOW);
					walking++;
				}
			}
		}
	}

	/** How many guards a full watch on the wall is: two to a tower and one to a gate, and never more than a third of the grown citizens. */
	private static int fullWatch(Settlement settlement, List<int[]> posts) {
		// With hillsides to level, one to a tower will do: the rest take up shovels.
		int perTower = standsDown(settlement) ? 0 : 1;
		int watch = 0;
		for (int[] post : posts) {
			if (post[1] <= perTower) {
				watch++;
			}
		}
		return Math.min(watch, (settlement.size() - settlement.children()) / 3);
	}

	/**
	 * How many guards patrol inside the settlement: as many as stand on the wall, where the
	 * settlement can spare them. They are not conjured up: they are citizens taken from other
	 * work, so half the grown citizens are always left to do that work, and a small place, which
	 * has no hands to spare, has few patrols or none.
	 */
	static int patrolsWanted(Settlement settlement, int onTheWall) {
		int adults = settlement.size() - settlement.children();
		int patrols = Math.max(0, Math.min(onTheWall, adults - onTheWall - adults / 2));
		// When the store is all but out of something, half the patrols put down their arms and
		// help bring it in; the wall stays manned.
		boolean dire = settlement.shortage(Commodity.FOOD) == 2 || settlement.shortage(Commodity.WOOD) == 2 || settlement.shortage(Commodity.STONE) == 2;
		if (standsDown(settlement)) {
			patrols /= 4;
		}
		return dire ? patrols / 2 : patrols;
	}

	/**
	 * Whether most of the militia can be spared for other work: the settlement has a great deal
	 * of ground to level. The towers and gates keep one guard each and a few walk the rounds;
	 * the guards that danger itself calls for (see {@link #wantedGuards}) are not touched.
	 */
	private static boolean standsDown(Settlement settlement) {
		return settlement.hasHeavyGroundWork();
	}

	/** Every place a guard can be posted to, as {building id, place on it}, in the order they are filled. */
	private static List<int[]> posts(Settlement settlement) {
		List<Building> towers = new ArrayList<>();
		List<Building> gates = new ArrayList<>();
		for (Building building : settlement.buildings()) {
			if (building.stage() == Building.Stage.COMPLETE) {
				if (Fortification.isTower(building.blueprint())) {
					towers.add(building);
				} else if (Fortification.isGate(building.blueprint())) {
					gates.add(building);
				}
			}
		}
		List<int[]> posts = new ArrayList<>();
		for (int place = 0; place < GUARDS_PER_TOWER; place++) {
			for (Building tower : towers) {
				posts.add(new int[] {tower.id(), place});
			}
			if (place == 0) {
				for (Building gate : gates) {
					posts.add(new int[] {gate.id(), 0});
				}
			}
		}
		return posts;
	}

	/**
	 * Those who fight for the settlement are given armour, made from the ore in its store: one
	 * suit each, as the ore allows. A suit once made is kept, whatever its wearer does later.
	 */
	private static void armDefenders(Settlement settlement) {
		Ledger ledger = settlement.ledger();
		for (Citizen citizen : settlement.members()) {
			// A smith makes a suit from two thirds of the ore.
			int ore = settlement.hasWorker(JobType.SMITH) ? ARMOR_ORE * 2 / 3 : ARMOR_ORE;
			if (citizen.isDefender() && !citizen.isArmored() && ledger.available(Commodity.ORE) >= ore) {
				ledger.take(Commodity.ORE, ore);
				citizen.setArmored(true);
			}
		}
	}

	/** How many guards the settlement wants: a few for its size, more for the danger it is in. */
	public static int wantedGuards(Settlement settlement) {
		int adults = settlement.size() - settlement.children();
		int wanted = switch (settlement.stage()) {
			case FOUNDING_CAMP -> 0;
			case SETTLEMENT -> 1;
			default -> 2;
		};
		wanted += settlement.threat() / THREAT_PER_GUARD;
		if (settlement.hasPlan(Policy.MILITARY)) {
			wanted++;
		}
		// Never more than a quarter of the grown citizens: somebody has to bring in the harvest.
		wanted = Math.min(wanted, adults / 4);
		// A wall with nobody on it guards nothing: its posts are manned as well, up to a third of
		// the grown citizens in all. Two to a tower and one to a gate is a full watch; a third
		// tower guard is posted only if the militia has grown beyond that for other reasons.
		List<int[]> posts = posts(settlement);
		int watch = fullWatch(settlement, posts);
		return Math.max(wanted, watch) + patrolsWanted(settlement, Math.min(posts.size(), watch));
	}

	/**
	 * The trade with the most hands among those whose product the store is not short of, not
	 * counting hands already called to the militia; null if every such trade is down to a few.
	 */
	private static JobType spareTrade(Settlement settlement) {
		JobType best = null;
		int most = 2; // a trade keeps at least two
		for (JobType job : new JobType[] {JobType.FARM, JobType.LUMBER, JobType.MINE}) {
			if (settlement.status(job.product()) != StockStatus.OK) {
				continue;
			}
			int hands = 0;
			for (Citizen citizen : settlement.members()) {
				if (citizen.job() == job && !citizen.isGuard()) {
					hands++;
				}
			}
			if (hands > most) {
				most = hands;
				best = job;
			}
		}
		return best;
	}

	private static void assignGuards(Settlement settlement) {
		int wanted = wantedGuards(settlement);
		int guards = 0;
		for (Citizen citizen : settlement.members()) {
			if (citizen.isGuard() && (citizen.isChild() || citizen.role() == Role.LEADER || citizen.role() == Role.GENERAL)) {
				citizen.setGuard(false);
			}
			if (citizen.isGuard()) {
				guards++;
			}
		}
		// Called up first are those the settlement can best spare: whoever has no work, then
		// whoever does work it has plenty of hands and stock for (the busiest such trade first),
		// and only then anyone at all. Among equals the brave go first.
		JobType spare = spareTrade(settlement);
		for (int pass = 0; pass < 6 && guards < wanted; pass++) {
			for (Citizen citizen : settlement.members()) {
				if (guards >= wanted) {
					break;
				}
				// Not the keepers of the enchantments: a librarian is never called from its lectern.
				boolean eligible = !citizen.isGuard() && !citizen.isChild() && citizen.job() != JobType.STUDY
						&& citizen.role() != Role.LEADER && citizen.role() != Role.GENERAL;
				boolean fromHere = pass < 2 ? citizen.job() == null : pass < 4 ? citizen.job() == spare && spare != null : true;
				if (eligible && fromHere && (pass % 2 == 1 || citizen.has(Trait.BRAVE))) {
					citizen.setGuard(true);
					guards++;
					if (pass >= 2 && pass < 4) {
						spare = null; // one at a time from a trade: see which is the fullest now
					}
				}
			}
			if (pass == 3 && guards < wanted && spare == null && (spare = spareTrade(settlement)) != null) {
				pass = 1; // and round again while there is a trade with hands to spare
			}
		}
		for (int pass = 0; pass < 2 && guards > wanted; pass++) {
			for (Citizen citizen : settlement.members()) {
				if (guards <= wanted) {
					break;
				}
				if (citizen.isGuard() && (pass == 1 || !citizen.has(Trait.BRAVE))) {
					citizen.setGuard(false);
					guards--;
				}
			}
		}
	}
}
