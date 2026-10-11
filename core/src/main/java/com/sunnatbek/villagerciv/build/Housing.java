package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Settlement;

/**
 * Who sleeps where. Every finished building with beds takes in as many citizens as it has beds;
 * a proper house is preferred to the first rough shelter. Those left without a bed spend the
 * night by the Town Center.
 */
public final class Housing {
	private Housing() {
	}

	public static void assign(Settlement settlement) {
		for (Citizen citizen : settlement.members()) {
			if (citizen.home() != Citizen.NO_HOME && !isHome(settlement.building(citizen.home()), citizen.bed())) {
				citizen.setHome(Citizen.NO_HOME, 0);
			}
		}
		for (Citizen citizen : settlement.members()) {
			// Someone bedded down in the shelter moves into a house as soon as one has room.
			Building current = settlement.building(citizen.home());
			if (current != null && current.category() == Blueprint.Category.HOUSING) {
				continue;
			}
			if (!moveIn(settlement, citizen, Blueprint.Category.HOUSING) && current == null) {
				moveIn(settlement, citizen, Blueprint.Category.SHELTER);
			}
		}
	}

	private static boolean isHome(Building building, int bed) {
		return building != null && building.stage() != Building.Stage.UNDER_CONSTRUCTION && bed < building.beds();
	}

	private static boolean moveIn(Settlement settlement, Citizen citizen, Blueprint.Category category) {
		for (Building building : settlement.buildings()) {
			if (building.category() != category || building.stage() == Building.Stage.UNDER_CONSTRUCTION) {
				continue;
			}
			for (int bed = 0; bed < building.beds(); bed++) {
				if (isFree(settlement, building.id(), bed)) {
					citizen.setHome(building.id(), bed);
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isFree(Settlement settlement, int building, int bed) {
		for (Citizen other : settlement.members()) {
			if (other.home() == building && other.bed() == bed) {
				return false;
			}
		}
		return true;
	}
}
