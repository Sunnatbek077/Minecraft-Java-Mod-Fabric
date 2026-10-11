package com.sunnatbek.villagerciv.api;

import com.sunnatbek.villagerciv.sim.Commodity;

import java.util.UUID;

/**
 * A settlement opened a request to its patron, or one came to an end.
 *
 * @param player  the settlement's patron
 * @param purpose a blueprint id, or the food reserve marker
 */
public record RequestEvent(SettlementId settlement, UUID player, Kind kind, Commodity commodity, int amount, String purpose)
		implements SimEvent {
	public enum Kind {
		OPENED,
		/** Everything asked for was given. */
		FULFILLED,
		/** Time ran out; the settlement goes on without. */
		EXPIRED,
		/** The settlement managed on its own. */
		NO_LONGER_NEEDED
	}
}
