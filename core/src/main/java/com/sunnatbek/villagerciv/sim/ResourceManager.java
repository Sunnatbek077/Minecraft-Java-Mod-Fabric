package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.EventBus;
import com.sunnatbek.villagerciv.api.RequestEvent;
import com.sunnatbek.villagerciv.build.BlueprintLibrary;
import com.sunnatbek.villagerciv.build.Construction;

import java.util.List;
import java.util.UUID;

/**
 * Watches the stockpile, says when something is running short, and decides what to do about it.
 * Asking the player is the last thing it does, not the first. A shortage first changes who works
 * where (the job board reads the statuses set here); only if it lasts does the settlement turn
 * to its patron, and then with a request that has a reason, an amount and an end.
 *
 * <p>Nothing here can stall a settlement. A request nobody answers runs out and is closed, and
 * the settlement keeps going at its own pace.
 */
public final class ResourceManager {
	/** No more than this many requests are open at once. */
	public static final int MAX_OPEN_REQUESTS = 3;
	/** A shortage has to last this long before the patron is asked; real emergencies skip the wait. */
	public static final long ASK_AFTER_TICKS = ExpeditionUpkeep.TICKS_PER_DAY / 2;
	public static final long REQUEST_LIFETIME_TICKS = 3 * ExpeditionUpkeep.TICKS_PER_DAY;
	/** After a request closes, the same thing is not asked for again for this long. */
	public static final long ASK_AGAIN_AFTER_TICKS = ExpeditionUpkeep.TICKS_PER_DAY;

	/** Food cover, in days. Entering a status and leaving it use different marks, so it does not flicker. */
	private static final int FOOD_LOW_BELOW = 5;
	private static final int FOOD_OK_FROM = 7;
	private static final int FOOD_CRITICAL_BELOW = 1;
	private static final int FOOD_NOT_CRITICAL_FROM = 2;
	private static final int FOOD_REQUEST_DAYS = 5;

	/** Reputation, in tenths of a point per unit given. */
	private static final int REQUESTED_UNIT = 30;
	private static final int NEEDED_UNIT = 10;
	private static final int UNNEEDED_UNIT = 1;

	private static final Commodity[] ASKABLE = {Commodity.FOOD, Commodity.WOOD, Commodity.STONE};

	private ResourceManager() {
	}

	static void update(Settlement settlement, BlueprintLibrary library, long gameTick, EventBus events) {
		for (Commodity commodity : ASKABLE) {
			StockStatus status = assess(settlement, library, commodity);
			settlement.setStatus(commodity, status, gameTick);
		}

		// Requests that ran out, or that the settlement no longer needs answered.
		for (Request request : List.copyOf(settlement.requests())) {
			if (settlement.status(request.commodity()) == StockStatus.OK) {
				close(settlement, request, RequestEvent.Kind.NO_LONGER_NEEDED, gameTick, events);
			} else if (gameTick > request.deadlineTick()) {
				close(settlement, request, RequestEvent.Kind.EXPIRED, gameTick, events);
			}
		}

		for (Commodity commodity : ASKABLE) {
			StockStatus status = settlement.status(commodity);
			if (status == StockStatus.OK || settlement.requests().size() >= MAX_OPEN_REQUESTS
					|| settlement.requestFor(commodity) != null || gameTick < settlement.askAgainTick(commodity)) {
				continue;
			}
			if (status != StockStatus.CRITICAL && gameTick - settlement.statusSinceTick(commodity) < ASK_AFTER_TICKS) {
				continue; // give the settlement's own people the chance to deal with it first
			}
			int amount = shortfall(settlement, library, commodity);
			if (amount <= 0) {
				continue;
			}
			String purpose = commodity == Commodity.FOOD ? Request.FOOD_RESERVE : Construction.wantedBlueprint(settlement, library);
			Request request = settlement.openRequest(commodity, amount, purpose, gameTick, gameTick + REQUEST_LIFETIME_TICKS);
			events.publish(new RequestEvent(settlement.id(), settlement.founder(), RequestEvent.Kind.OPENED, commodity, request.amount(), purpose));
		}
	}

	private static StockStatus assess(Settlement settlement, BlueprintLibrary library, Commodity commodity) {
		StockStatus current = settlement.status(commodity);
		if (commodity == Commodity.FOOD) {
			int days = settlement.foodDays();
			if (settlement.isStarving() || days < FOOD_CRITICAL_BELOW || (current == StockStatus.CRITICAL && days < FOOD_NOT_CRITICAL_FROM)) {
				return StockStatus.CRITICAL;
			}
			return days < FOOD_LOW_BELOW || (current != StockStatus.OK && days < FOOD_OK_FROM) ? StockStatus.LOW : StockStatus.OK;
		}
		// Building materials are short when the next thing the settlement wants cannot be paid for.
		int need = Construction.materialNeed(settlement, library, commodity);
		int available = settlement.ledger().available(commodity);
		return available < need || (current != StockStatus.OK && available * 10 < need * 11) ? StockStatus.LOW : StockStatus.OK;
	}

	/** How much to ask for, rounded up to a round number. */
	private static int shortfall(Settlement settlement, BlueprintLibrary library, Commodity commodity) {
		int missing;
		if (commodity == Commodity.FOOD) {
			int mouthsDoubled = 2 * settlement.size() - settlement.children();
			missing = FOOD_REQUEST_DAYS * mouthsDoubled / 2 - settlement.ledger().onHand(Commodity.FOOD);
		} else {
			missing = Construction.materialNeed(settlement, library, commodity) - settlement.ledger().available(commodity);
		}
		return missing <= 0 ? 0 : (missing + 9) / 10 * 10;
	}

	private static void close(Settlement settlement, Request request, RequestEvent.Kind kind, long gameTick, EventBus events) {
		settlement.closeRequest(request.id(), gameTick + ASK_AGAIN_AFTER_TICKS);
		events.publish(new RequestEvent(settlement.id(), settlement.founder(), kind, request.commodity(), request.amount(), request.purpose()));
	}

	/**
	 * A player gave goods. They go into the stockpile, count towards an open request, and earn
	 * standing according to how much they were wanted: most for what was asked for, something
	 * for what is short, next to nothing for what the settlement has plenty of. Dumping junk on
	 * a settlement does not buy its regard.
	 */
	static void contribute(Settlement settlement, UUID player, Commodity commodity, int units, long gameTick, EventBus events) {
		Request request = settlement.requestFor(commodity);
		int requested = request == null ? 0 : Math.min(units, request.remaining());
		int rest = units - requested;
		int points = requested * REQUESTED_UNIT + rest * (settlement.status(commodity) == StockStatus.OK ? UNNEEDED_UNIT : NEEDED_UNIT);
		settlement.contribute(player, commodity, units);
		settlement.addReputation(player, points);
		if (request != null && requested > 0) {
			Request updated = settlement.deliverTo(request.id(), requested);
			if (updated.remaining() == 0) {
				close(settlement, updated, RequestEvent.Kind.FULFILLED, gameTick, events);
			}
		}
	}
}
