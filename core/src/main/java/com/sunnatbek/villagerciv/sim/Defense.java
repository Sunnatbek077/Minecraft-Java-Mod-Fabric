package com.sunnatbek.villagerciv.sim;

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
	 * Guards are posted on the wall: on the towers and above the gates. One goes to every tower
	 * first, then one to every gate, then a second and a third to every tower, for as long as
	 * there are guards to post. Any left over keep the old night watch on foot.
	 */
	private static void manPosts(Settlement settlement) {
		List<int[]> posts = posts(settlement);
		int next = 0;
		for (Citizen citizen : settlement.members()) {
			if (citizen.isGuard() && next < posts.size()) {
				citizen.setPost(posts.get(next)[0], posts.get(next)[1]);
				next++;
			} else {
				citizen.setPost(Citizen.NO_POST, 0);
			}
		}
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
		int towers = 0;
		int others = 0;
		for (int[] post : posts(settlement)) {
			if (post[1] == 0) {
				others++;
			} else if (post[1] == 1) {
				towers++;
			}
		}
		return Math.max(wanted, Math.min(others + towers, adults / 3));
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
		// The brave are called up first, and are the last to be stood down.
		for (int pass = 0; pass < 2 && guards < wanted; pass++) {
			for (Citizen citizen : settlement.members()) {
				if (guards >= wanted) {
					break;
				}
				boolean eligible = !citizen.isGuard() && !citizen.isChild()
						&& citizen.role() != Role.LEADER && citizen.role() != Role.GENERAL;
				if (eligible && (pass == 1 || citizen.has(Trait.BRAVE))) {
					citizen.setGuard(true);
					guards++;
				}
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
