package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenId;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Whatever citizens currently belong to: a travelling expedition or a settlement. */
public sealed interface CitizenGroup permits Expedition, Settlement {
	Collection<Citizen> members();

	Optional<Citizen> member(CitizenId id);

	int size();

	/** True while the group does not have enough food for everyone. */
	boolean isStarving();

	/** The player this group has a relationship with: the one it follows, or its founding patron. */
	UUID player();
}
