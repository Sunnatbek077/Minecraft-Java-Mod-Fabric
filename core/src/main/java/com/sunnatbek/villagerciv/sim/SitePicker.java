package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Street;

/**
 * Chooses where a settlement works. Sites are picked from the surveyed plot map alone, so the
 * choice is cheap, repeatable, and needs no look at the world.
 */
public final class SitePicker {
	/** Keep work sites clear of the Town Center and of each other. */
	private static final int MIN_DISTANCE = 12;
	private static final int MAX_DISTANCE = 40;
	private static final int MARGIN = 2;
	private static final int STEP = 3;

	private SitePicker() {
	}

	/** Makes sure the settlement has one site for every job it can place. Returns true if any was added. */
	public static boolean ensureSites(Settlement settlement) {
		if (settlement.plots().size() == 0) {
			return false;
		}
		boolean added = false;
		for (JobType type : JobType.values()) {
			if (!type.hasWorkSite()) {
				continue;
			}
			boolean present = false;
			for (WorkSite site : settlement.sites()) {
				if (site.type() == type && !site.isExhausted()) {
					present = true;
					break;
				}
			}
			if (!present) {
				added |= place(settlement, type);
			}
		}
		return added | expand(settlement);
	}

	/** At most this many sites of one kind in a camp; every stage after that allows two more. */
	private static final int MAX_SITES_PER_JOB = 4;

	/**
	 * A grown settlement has more hands than its first sites can use. When two or more workers
	 * stand idle, another site is opened: for whatever is short, or else for the job with the
	 * fewest sites, leaning the way the Development Plan points.
	 */
	private static boolean expand(Settlement settlement) {
		// A settlement helps itself before it asks for help. Something running short, with every
		// place that yields it fully manned, calls for another such place at once; the job board
		// then moves hands to it from where they are needed less. A camp is too small for that:
		// its few people are better kept together.
		if (settlement.stage().ordinal() >= SettlementStage.SETTLEMENT.ordinal()) {
			for (JobType type : JobType.values()) {
				if (!type.hasWorkSite() || settlement.status(type.product()) == StockStatus.OK) {
					continue;
				}
				int sites = 0;
				int free = 0;
				for (WorkSite site : settlement.sites()) {
					if (site.type() == type && !site.isExhausted()) {
						sites++;
						free += Labor.slots(settlement, type) - holders(settlement, site);
					}
				}
				if (free <= 0 && sites < MAX_SITES_PER_JOB + 2 * settlement.stage().ordinal() && place(settlement, type)) {
					return true;
				}
			}
		}
		int idle = 0;
		for (Citizen citizen : settlement.members()) {
			if (citizen.job() == null && !citizen.isChild() && citizen.role() != Role.LEADER && citizen.role() != Role.GENERAL) {
				idle++;
			}
		}
		if (idle < 2) {
			return false;
		}
		// Idle hands only call for a new site if the existing ones are full.
		for (WorkSite site : settlement.sites()) {
			if (site.isExhausted()) {
				continue;
			}
			if (holders(settlement, site) < Labor.slots(settlement, site.type())) {
				return false;
			}
		}
		JobType best = null;
		int bestScore = Integer.MIN_VALUE;
		for (JobType type : JobType.values()) {
			if (!type.hasWorkSite()) {
				continue;
			}
			int sites = 0;
			for (WorkSite site : settlement.sites()) {
				if (site.type() == type && !site.isExhausted()) {
					sites++;
				}
			}
			if (sites >= MAX_SITES_PER_JOB + 2 * settlement.stage().ordinal()) {
				continue;
			}
			int score = -sites * 10 + settlement.workBonusPercent(type);
			if (settlement.status(type.product()) != StockStatus.OK) {
				score += 100;
			}
			if (score > bestScore) {
				best = type;
				bestScore = score;
			}
		}
		return best != null && place(settlement, best);
	}

	private static int holders(Settlement settlement, WorkSite site) {
		int holders = 0;
		for (Citizen citizen : settlement.members()) {
			if (citizen.job() == site.type() && citizen.jobSite() == site.id()) {
				holders++;
			}
		}
		return holders;
	}

	/**
	 * Searches outwards ring by ring for a fully buildable square. Each job starts looking in a
	 * different direction, which spreads the sites around the centre instead of lining them up.
	 */
	private static boolean place(Settlement settlement, JobType type) {
		Settlement.PlotMap plots = settlement.plots();
		int startAngle = type.ordinal() * 120;
		for (int distance = MIN_DISTANCE; distance <= MAX_DISTANCE; distance += STEP) {
			for (int turn = 0; turn < 360; turn += 15) {
				double angle = Math.toRadians(startAngle + turn);
				int x = settlement.anchorX() + (int) Math.round(Math.cos(angle) * distance);
				int z = settlement.anchorZ() + (int) Math.round(Math.sin(angle) * distance);
				if (fits(settlement, plots, x, z)) {
					settlement.addSite(type, x, z);
					return true;
				}
			}
		}
		return false;
	}

	private static boolean fits(Settlement settlement, Settlement.PlotMap plots, int x, int z) {
		int half = WorkSite.HALF_WIDTH;
		// Fields, groves and mines stay inside the wall like everything else.
		if (!settlement.withinWalls(x - half, z - half) || !settlement.withinWalls(x + half, z + half)) {
			return false;
		}
		for (int dz = -half; dz <= half; dz++) {
			for (int dx = -half; dx <= half; dx++) {
				if (!plots.isBuildable(x + dx, z + dz)) {
					return false;
				}
			}
		}
		// Not on a building's lot or across a street: the town plan was there first.
		for (Building building : settlement.buildings()) {
			if (x + half >= building.x() - 1 && x - half <= building.x() + building.sizeX()
					&& z + half >= building.z() - 1 && z - half <= building.z() + building.sizeZ()) {
				return false;
			}
		}
		for (Street street : settlement.streets()) {
			for (int along = 0; along < street.length(); along++) {
				if (Math.abs(street.cellX(along, 0) - x) <= half + 1 && Math.abs(street.cellZ(along, 0) - z) <= half + 1) {
					return false;
				}
			}
		}
		int clearance = 2 * half + 1 + MARGIN;
		for (WorkSite other : settlement.sites()) {
			if (Math.abs(other.x() - x) < clearance && Math.abs(other.z() - z) < clearance) {
				return false;
			}
		}
		return true;
	}
}
