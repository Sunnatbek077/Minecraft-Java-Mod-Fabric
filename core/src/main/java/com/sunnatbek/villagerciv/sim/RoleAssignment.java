package com.sunnatbek.villagerciv.sim;

import java.util.Collection;

/**
 * The group organises itself: the player picks who joins, never who does what. Roles are
 * positions, so this is simply run again whenever the group changes (someone joins or dies).
 */
public final class RoleAssignment {
	private RoleAssignment() {
	}

	public static void assign(Collection<Citizen> members) {
		Citizen leader = null;
		for (Citizen citizen : members) {
			if (citizen.isChild()) {
				continue;
			}
			if (leader == null || leaderScore(citizen) > leaderScore(leader)) {
				leader = citizen;
			}
		}
		Citizen general = null;
		for (Citizen citizen : members) {
			if (citizen != leader && !citizen.isChild() && generalScore(citizen) > 0 && (general == null || generalScore(citizen) > generalScore(general))) {
				general = citizen;
			}
		}
		for (Citizen citizen : members) {
			if (citizen.isChild()) {
				continue;
			}
			if (citizen == leader) {
				citizen.setRole(Role.LEADER);
			} else if (citizen == general) {
				citizen.setRole(Role.GENERAL);
			} else {
				// A fighter who is not the general falls back to general labour.
				citizen.setRole(citizen.affinity() == Role.GENERAL ? Role.LABORER : citizen.affinity());
			}
		}
	}

	private static int leaderScore(Citizen citizen) {
		// The sitting leader keeps the position unless someone is clearly better suited.
		return citizen.loyalty() + (citizen.has(Trait.CHARISMATIC) ? 50 : 0) + (citizen.role() == Role.LEADER ? 10 : 0);
	}

	private static int generalScore(Citizen citizen) {
		return (citizen.has(Trait.BRAVE) ? 2 : 0) + (citizen.affinity() == Role.GENERAL ? 1 : 0);
	}
}
