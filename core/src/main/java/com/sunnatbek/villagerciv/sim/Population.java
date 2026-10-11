package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenBornEvent;
import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.api.EventBus;
import com.sunnatbek.villagerciv.api.SettlementStageEvent;
import com.sunnatbek.villagerciv.build.Blueprint;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * How a settlement grows. Food feeds people, people become hands, hands grow more food: a loop
 * that would run away if nothing held it back. Three things do. Children are born only where
 * there is a spare bed, so housing has to come first. A child eats before it works. And a
 * settlement cannot outgrow its stage: past that size it has to become something more first.
 * A settlement that is well stocked also draws grown newcomers, which is the quicker road.
 */
public final class Population {
	/** Food in store, in days, below which nobody thinks of children or of building for growth. */
	public static final int COMFORTABLE_FOOD_DAYS = 3;
	/** How long a child takes to grow up. */
	public static final long CHILDHOOD_TICKS = ExpeditionUpkeep.TICKS_PER_DAY;
	/** A parent has no further child for this long. */
	public static final long BIRTH_COOLDOWN_TICKS = ExpeditionUpkeep.TICKS_PER_DAY;
	/** How long a settlement must hold everything its next stage asks before it is promoted. */
	public static final long PROMOTION_HOLD_TICKS = ExpeditionUpkeep.TICKS_PER_DAY / 4;
	/** How long a settlement must stay too small for its stage before it loses it. */
	public static final long DECLINE_HOLD_TICKS = ExpeditionUpkeep.TICKS_PER_DAY;
	/** What settling a newcomer in takes out of the store: a welcome, tools of a sort, a bed made up. */
	public static final int ARRIVAL_FOOD = 10;
	public static final int ARRIVAL_WOOD = 30;
	public static final int ARRIVAL_STONE = 15;

	private static final long STEP_TICKS = ExpeditionUpkeep.STEP_TICKS;
	private static final int MIN_MORALE_FOR_BIRTHS = 50;
	private static final int VILLAGE_FOOD_DAYS = 5;
	private static final int VILLAGE_MORALE = 60;
	/** Chance of a birth per step (a tenth of a day) when there is plenty of room. */
	private static final double BIRTH_CHANCE = 0.3;
	/** Chance per step that word of a prosperous place brings somebody to it. */
	private static final double ARRIVAL_CHANCE = 0.25;
	private static final int ARRIVAL_FOOD_DAYS = 5;
	private static final int ARRIVAL_MORALE = 60;

	private static final Role[] TRADES = {Role.FARMER, Role.LUMBERJACK, Role.MINER, Role.BUILDER, Role.LABORER};

	private Population() {
	}

	/** Largest population a settlement of this stage grows to on its own. */
	public static int softCap(SettlementStage stage) {
		return switch (stage) {
			case FOUNDING_CAMP -> 10;
			case SETTLEMENT -> 25;
			case VILLAGE -> 60;
			case TOWN -> 150;
			case CITY -> 400;
		};
	}

	/**
	 * Size at which a settlement of this stage is ready to become the next. Well below the cap:
	 * a place moves up while it is still growing, rather than filling to the brim and waiting.
	 */
	public static int promotionSize(SettlementStage stage) {
		return switch (stage) {
			case FOUNDING_CAMP -> 6;
			case SETTLEMENT -> 16;
			case VILLAGE -> 40;
			case TOWN -> 100;
			case CITY -> Integer.MAX_VALUE;
		};
	}

	static void update(Settlement settlement, long gameTick, IdGenerator random, EventBus events) {
		long behind = (gameTick - settlement.lastPopulationTick()) / STEP_TICKS;
		if (behind <= 0) {
			return;
		}
		// A long gap counts as one step: growth is not replayed, it simply resumes.
		settlement.setLastPopulationTick(gameTick - (gameTick - settlement.lastPopulationTick()) % STEP_TICKS);

		growUp(settlement, gameTick);
		settlement.setMorale(morale(settlement));
		promote(settlement, gameTick, events);
		birth(settlement, gameTick, random, events);
		arrival(settlement, gameTick, random, events);
	}

	private static void growUp(Settlement settlement, long gameTick) {
		boolean anyone = false;
		for (Citizen citizen : settlement.members()) {
			if (citizen.isChild() && gameTick >= citizen.adultAtTick()) {
				citizen.setRole(citizen.affinity());
				anyone = true;
			}
		}
		if (anyone) {
			RoleAssignment.assign(settlement.members());
		}
	}

	/** Food, a bed, and trust in how things are going. */
	static int morale(Settlement settlement) {
		int size = settlement.size();
		if (size == 0) {
			return 50;
		}
		int morale = 50;
		if (settlement.isStarving()) {
			morale -= 30;
		} else if (settlement.foodDays() >= 5) {
			morale += 15;
		} else if (settlement.foodDays() >= COMFORTABLE_FOOD_DAYS) {
			morale += 8;
		}
		int homeless = 0;
		int loyalty = 0;
		for (Citizen citizen : settlement.members()) {
			if (citizen.home() == Citizen.NO_HOME) {
				homeless++;
			}
			loyalty += citizen.loyalty();
		}
		morale += 15 - 30 * homeless / size;
		morale += (loyalty / size - 60) / 2;
		if (settlement.hasPlan(Policy.MILITARY)) {
			morale += 5; // people sleep easier in a place that looks to its defence
		}
		return Math.clamp(morale, 0, 100);
	}

	/**
	 * A settlement moves up a stage once it has held everything that stage asks for a while; a
	 * single good day is not enough. Every step also takes a Development Plan: without a
	 * direction a settlement lives on as it is, but grows into nothing more.
	 */
	private static void promote(Settlement settlement, long gameTick, EventBus events) {
		SettlementStage next = switch (settlement.stage()) {
			case FOUNDING_CAMP -> SettlementStage.SETTLEMENT;
			case SETTLEMENT -> SettlementStage.VILLAGE;
			case VILLAGE -> SettlementStage.TOWN;
			case TOWN -> SettlementStage.CITY;
			case CITY -> null;
		};
		if (decline(settlement, gameTick, events) || next == null) {
			return;
		}
		boolean eligible = settlement.hasAnyPlan()
				&& settlement.size() >= promotionSize(settlement.stage())
				&& settlement.housingCapacity() >= settlement.size()
				&& !settlement.isStarving() && meetsStage(settlement, next);
		if (!eligible) {
			settlement.setPromotionSinceTick(Settlement.NOT_ELIGIBLE);
		} else if (settlement.promotionSinceTick() == Settlement.NOT_ELIGIBLE) {
			settlement.setPromotionSinceTick(gameTick);
		} else if (gameTick - settlement.promotionSinceTick() >= PROMOTION_HOLD_TICKS) {
			settlement.setStage(next);
			settlement.setPromotionSinceTick(Settlement.NOT_ELIGIBLE);
			events.publish(new SettlementStageEvent(settlement.id(), settlement.founder(), false));
		}
	}

	/**
	 * A settlement that has shrunk to under half of what its stage took to reach, and stays so
	 * for a day, falls back a stage. Half, not the full figure: a stage is not lost to one bad
	 * night, and a place hovering round the mark does not flicker between two names.
	 *
	 * @return whether it is in decline (and so cannot be rising)
	 */
	private static boolean decline(Settlement settlement, long gameTick, EventBus events) {
		SettlementStage stage = settlement.stage();
		if (stage == SettlementStage.FOUNDING_CAMP) {
			return false;
		}
		SettlementStage below = SettlementStage.values()[stage.ordinal() - 1];
		if (settlement.size() * 2 >= promotionSize(below)) {
			settlement.setDeclineSinceTick(Settlement.NOT_ELIGIBLE);
			return false;
		}
		if (settlement.declineSinceTick() == Settlement.NOT_ELIGIBLE) {
			settlement.setDeclineSinceTick(gameTick);
		} else if (gameTick - settlement.declineSinceTick() >= DECLINE_HOLD_TICKS) {
			settlement.setStage(below);
			settlement.setDeclineSinceTick(Settlement.NOT_ELIGIBLE);
			settlement.setPromotionSinceTick(Settlement.NOT_ELIGIBLE);
			events.publish(new SettlementStageEvent(settlement.id(), settlement.founder(), true));
		}
		return true;
	}

	/**
	 * The trade a child is brought up to. Usually a parent's; but where grown people are doing
	 * work they were never taught, the child is apprenticed to the trade with the most such
	 * hands in it, and grows up knowing it properly.
	 */
	private static Role apprenticeship(Settlement settlement, Role parents) {
		JobType neediest = null;
		int most = 1; // two untaught hands in a trade before a child is put to it
		for (JobType job : JobType.values()) {
			int untaught = 0;
			for (Citizen citizen : settlement.members()) {
				if (citizen.job() == job && citizen.affinity() != job.affinity()) {
					untaught++;
				}
			}
			if (untaught > most) {
				most = untaught;
				neediest = job;
			}
		}
		return neediest == null ? parents : neediest.affinity();
	}

	/** What a stage asks beyond numbers, beds and a plan. */
	private static boolean meetsStage(Settlement settlement, SettlementStage stage) {
		boolean field = false;
		for (WorkSite site : settlement.sites()) {
			field |= site.type() == JobType.FARM;
		}
		return switch (stage) {
			// A store, a field, and food to spare.
			case SETTLEMENT -> settlement.hasBuilding(Blueprint.Category.STORAGE) && field
					&& settlement.foodDays() >= COMFORTABLE_FOOD_DAYS;
			// A larger reserve, and people who are content.
			case VILLAGE -> settlement.foodDays() >= VILLAGE_FOOD_DAYS && settlement.morale() >= VILLAGE_MORALE;
			// Trades of its own: a town has a smith and a mason at work, and keeps a good reserve.
			case TOWN -> settlement.foodDays() >= VILLAGE_FOOD_DAYS && settlement.morale() >= VILLAGE_MORALE
					&& settlement.hasWorker(JobType.SMITH) && settlement.hasWorker(JobType.MASON);
			// A market town grown great: a city trades.
			case CITY -> settlement.foodDays() >= VILLAGE_FOOD_DAYS && settlement.morale() >= VILLAGE_MORALE
					&& settlement.hasWorker(JobType.TRADE);
			default -> false;
		};
	}

	private static void birth(Settlement settlement, long gameTick, IdGenerator random, EventBus events) {
		int size = settlement.size();
		// Spare beds are what make room for a child; the stage only says where growth stops.
		int room = size >= softCap(settlement.stage()) ? 0 : settlement.housingCapacity() - size;
		if (room <= 0 || settlement.isStarving() || settlement.foodDays() < COMFORTABLE_FOOD_DAYS
				|| settlement.morale() < MIN_MORALE_FOR_BIRTHS) {
			return;
		}
		List<Citizen> parents = new ArrayList<>();
		for (Citizen citizen : settlement.members()) {
			if (!citizen.isChild() && gameTick - citizen.lastChildTick() >= BIRTH_COOLDOWN_TICKS) {
				parents.add(citizen);
			}
		}
		if (parents.size() < 2) {
			return;
		}
		// The fuller the houses, the rarer a birth: growth eases off as it nears what there is room for.
		double chance = BIRTH_CHANCE * Math.min(1.0, room / 3.0) * settlement.birthPercent() / 100.0;
		if (random.nextDouble() >= chance) {
			return;
		}
		Citizen first = parents.remove(random.nextInt(parents.size()));
		Citizen second = parents.get(random.nextInt(parents.size()));

		// A child takes after its parents: the trade of one, a trait of each.
		EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
		for (Citizen parent : List.of(first, second)) {
			List<Trait> options = new ArrayList<>(parent.traits());
			if (!options.isEmpty()) {
				traits.add(options.get(random.nextInt(options.size())));
			}
		}
		Role affinity = apprenticeship(settlement, (random.nextInt(2) == 0 ? first : second).affinity());
		Citizen child = new Citizen(new CitizenId(random.next()), CitizenNames.pick(random, settlement.members()), gameTick, affinity, traits);
		child.setRole(Role.CHILD);
		child.setAdultAtTick(gameTick + CHILDHOOD_TICKS);
		child.setLoyalty((first.loyalty() + second.loyalty()) / 2);
		// Born at home, or by the Town Center if its parent has none.
		child.setPosition(first.x(), first.y(), first.z());
		settlement.add(child);
		first.setLastChildTick(gameTick);
		second.setLastChildTick(gameTick);
		events.publish(new CitizenBornEvent(settlement.id(), settlement.founder(), child.id(), child.name(), false));
	}

	/**
	 * A settlement with empty beds and full stores draws people to it. This is where a patron's
	 * gifts turn into growth: a newcomer is a grown pair of hands from the day they arrive, and
	 * settling them in is paid for out of the store.
	 */
	private static void arrival(Settlement settlement, long gameTick, IdGenerator random, EventBus events) {
		int size = settlement.size();
		int room = size >= softCap(settlement.stage()) ? 0 : settlement.housingCapacity() - size;
		Ledger ledger = settlement.ledger();
		if (room <= 0 || !settlement.hasAnyPlan() || settlement.isStarving()
				|| settlement.foodDays() < ARRIVAL_FOOD_DAYS || settlement.morale() < ARRIVAL_MORALE
				|| ledger.available(Commodity.FOOD) < ARRIVAL_FOOD || ledger.available(Commodity.WOOD) < ARRIVAL_WOOD
				|| ledger.available(Commodity.STONE) < ARRIVAL_STONE) {
			return;
		}
		if (random.nextDouble() >= ARRIVAL_CHANCE) {
			return;
		}
		ledger.take(Commodity.FOOD, ARRIVAL_FOOD);
		ledger.take(Commodity.WOOD, ARRIVAL_WOOD);
		ledger.take(Commodity.STONE, ARRIVAL_STONE);

		Role affinity = TRADES[random.nextInt(TRADES.length)];
		EnumSet<Trait> traits = EnumSet.of(Trait.values()[random.nextInt(Trait.values().length)]);
		Citizen newcomer = new Citizen(new CitizenId(random.next()), CitizenNames.pick(random, settlement.members()), gameTick, affinity, traits);
		newcomer.setLoyalty(50);
		// They walk in by the Town Center.
		newcomer.setPosition(settlement.anchorX() + 0.5, settlement.anchorY() + 1, settlement.anchorZ() + 2.5);
		settlement.add(newcomer);
		RoleAssignment.assign(settlement.members());
		events.publish(new CitizenBornEvent(settlement.id(), settlement.founder(), newcomer.id(), newcomer.name(), true));
	}
}
