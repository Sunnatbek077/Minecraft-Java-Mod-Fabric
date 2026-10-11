package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.IdGenerator;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.WorkSite;

import java.util.ArrayList;
import java.util.List;

/**
 * Where things go in a settlement, laid out the way a village grows: streets run out from the
 * Town Center and branch, and every building takes a free lot along a street, facing it. Which
 * street, which side and where along it are left to chance, so no two settlements come out alike.
 *
 * <p>Everything is decided from the surveyed plot map and the records, never from the world.
 */
public final class TownPlan {
	public static final int STREET_LENGTH = 28;
	private static final int MAX_STREETS = 12;
	/** Nothing is built this close to the Town Center: it keeps a small square around it. */
	private static final int CENTER_CLEARANCE = 3;
	/** Off the streets, one cell in this many of a lot may be uneven or wet ground: the builders level it. */
	private static final int ROUGH_SHARE = 8;
	private static final int[] BRANCH_POINTS = {10, 18, 26};
	private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

	private TownPlan() {
	}

	/**
	 * Finds a lot for a building and records it, growing the street network if every existing
	 * street is full. Returns null when the settlement has no room left.
	 */
	public static Building site(Settlement settlement, Blueprint blueprint, IdGenerator random) {
		return site(settlement, blueprint, random, false, true);
	}

	/**
	 * @param central  take the free lot nearest the Town Center instead of any free lot
	 * @param mayGrow  whether a street may be added if no lot is found on the existing ones
	 */
	public static Building site(Settlement settlement, Blueprint blueprint, IdGenerator random, boolean central, boolean mayGrow) {
		for (int attempt = 0; attempt < 3; attempt++) {
			Building building = settlement.streets().isEmpty() ? null : findLot(settlement, blueprint, random, central);
			if (building != null) {
				return building;
			}
			if (!mayGrow || !growStreet(settlement, random)) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Finds room for a large building anywhere on the settlement's land, off the streets: the
	 * free square closest to the Town Center, turned to face it. For what is too big for a
	 * street lot, in a settlement that has already grown around its centre.
	 *
	 * @param reach how far from the Town Center to look
	 */
	public static Building freeSite(Settlement settlement, Blueprint blueprint, int reach) {
		int width = blueprint.width();
		int depth = blueprint.depth();
		int side = 2 * reach + 1;
		int originX = settlement.anchorX() - reach;
		int originZ = settlement.anchorZ() - reach;
		// Running totals, so that any rectangle is judged in one step. "Taken" cells belong to
		// something else and rule a place out. "Rough" ones are only uneven or wet: a lot is
		// levelled before it is built on, so some of those are put up with.
		int[][] taken = new int[side + 1][side + 1];
		int[][] rough = new int[side + 1][side + 1];
		for (int z = 0; z < side; z++) {
			for (int x = 0; x < side; x++) {
				int worldX = originX + x;
				int worldZ = originZ + z;
				boolean free = isFree(settlement, worldX, worldZ, null) && !onPlannedWall(settlement, worldX, worldZ);
				boolean even = settlement.plots().isBuildable(worldX, worldZ);
				taken[z + 1][x + 1] = taken[z][x + 1] + taken[z + 1][x] - taken[z][x] + (free ? 0 : 1);
				rough[z + 1][x + 1] = rough[z][x + 1] + rough[z + 1][x] - rough[z][x] + (even ? 0 : 1);
			}
		}
		int roughAllowed = width * depth / ROUGH_SHARE;
		long best = Long.MAX_VALUE;
		int bestX = 0;
		int bestZ = 0;
		int bestRotation = 0;
		for (int rotation = 0; rotation < 4; rotation++) {
			int sizeX = (rotation & 1) == 0 ? width : depth;
			int sizeZ = (rotation & 1) == 0 ? depth : width;
			for (int z = 0; z + sizeZ <= side; z++) {
				for (int x = 0; x + sizeX <= side; x++) {
					if (taken[z + sizeZ][x + sizeX] - taken[z][x + sizeX] - taken[z + sizeZ][x] + taken[z][x] != 0
							|| rough[z + sizeZ][x + sizeX] - rough[z][x + sizeX] - rough[z + sizeZ][x] + rough[z][x] > roughAllowed) {
						continue;
					}
					// Twice the offset of the lot's middle from the Town Center, to stay in whole numbers.
					long dx = 2L * (originX + x) + sizeX - 1 - 2L * settlement.anchorX();
					long dz = 2L * (originZ + z) + sizeZ - 1 - 2L * settlement.anchorZ();
					// The front is turned to the Town Center: south if the centre lies to the south, and so on.
					int facing = Math.abs(dz) >= Math.abs(dx) ? (dz < 0 ? 0 : 2) : (dx > 0 ? 1 : 3);
					if (facing != rotation) {
						continue;
					}
					long distance = dx * dx + dz * dz;
					if (distance < best) {
						best = distance;
						bestX = originX + x;
						bestZ = originZ + z;
						bestRotation = rotation;
					}
				}
			}
		}
		if (best == Long.MAX_VALUE) {
			return null;
		}
		return settlement.addBuilding(blueprint, bestX, bestZ, bestRotation, (bestRotation & 1) == 0 ? width : depth,
				(bestRotation & 1) == 0 ? depth : width, -1, 0, 0, ConstructionPlan.count(blueprint, 0));
	}

	private static boolean onPlannedWall(Settlement settlement, int x, int z) {
		for (WallPiece piece : settlement.wallPlan()) {
			if (x >= piece.x() - 1 && x <= piece.x() + piece.sizeX() && z >= piece.z() - 1 && z <= piece.z() + piece.sizeZ()) {
				return true;
			}
		}
		return false;
	}

	private static Building findLot(Settlement settlement, Blueprint blueprint, IdGenerator random, boolean central) {
		int width = blueprint.width();
		int depth = blueprint.depth();
		List<Street> streets = settlement.streets();
		for (int index = 0; index < streets.size(); index++) {
			Street street = streets.get(index);
			int firstSide = random.nextInt(2) == 0 ? 1 : -1;
			for (int side : new int[] {firstSide, -firstSide}) {
				List<Integer> starts = new ArrayList<>();
				for (int along = 2; along + width <= street.length(); along++) {
					if (lotIsFree(settlement, street, along, side, width, depth)) {
						starts.add(along);
					}
				}
				if (!starts.isEmpty()) {
					// Streets run outwards, so the first free stretch is the one closest in.
					return build(settlement, blueprint, index, central ? starts.getFirst() : starts.get(random.nextInt(starts.size())), side);
				}
			}
		}
		return null;
	}

	private static boolean lotIsFree(Settlement settlement, Street street, int along, int side, int width, int depth) {
		for (int i = 0; i < width; i++) {
			for (int t = 0; t < depth; t++) {
				int x = street.cellX(along + i, side * (Street.LOT_OFFSET + t));
				int z = street.cellZ(along + i, side * (Street.LOT_OFFSET + t));
				if (!settlement.plots().isBuildable(x, z) || !isFree(settlement, x, z, street)) {
					return false;
				}
			}
		}
		return true;
	}

	/** True if nothing else is planned on or right beside this cell. */
	private static boolean isFree(Settlement settlement, int x, int z, Street own) {
		if (!settlement.withinWalls(x, z)) {
			return false;
		}
		if (Math.abs(x - settlement.anchorX()) <= CENTER_CLEARANCE && Math.abs(z - settlement.anchorZ()) <= CENTER_CLEARANCE) {
			return false;
		}
		for (Building building : settlement.buildings()) {
			if (x >= building.x() - 1 && x <= building.x() + building.sizeX() && z >= building.z() - 1 && z <= building.z() + building.sizeZ()) {
				return false;
			}
		}
		for (WorkSite site : settlement.sites()) {
			if (Math.abs(x - site.x()) <= WorkSite.HALF_WIDTH + 1 && Math.abs(z - site.z()) <= WorkSite.HALF_WIDTH + 1) {
				return false;
			}
		}
		for (Street street : settlement.streets()) {
			if (street != own && street.covers(x, z)) {
				return false;
			}
		}
		return true;
	}

	private static Building build(Settlement settlement, Blueprint blueprint, int streetIndex, int along, int side) {
		Street street = settlement.streets().get(streetIndex);
		int width = blueprint.width();
		int depth = blueprint.depth();
		int x0 = street.cellX(along, side * Street.LOT_OFFSET);
		int z0 = street.cellZ(along, side * Street.LOT_OFFSET);
		int x1 = street.cellX(along + width - 1, side * (Street.LOT_OFFSET + depth - 1));
		int z1 = street.cellZ(along + width - 1, side * (Street.LOT_OFFSET + depth - 1));

		// The blueprint's front (its south side) is turned towards the street.
		int frontX = street.dz() * side;
		int frontZ = -street.dx() * side;
		int rotation = frontZ == 1 ? 0 : frontX == -1 ? 1 : frontZ == -1 ? 2 : 3;

		// The street is paved from where it ends now to just past this lot.
		int paveFrom = street.paved();
		int paveTo = Math.max(paveFrom, Math.min(street.length(), along + width + 1));
		street.setPaved(paveTo);
		int paveCells = (paveTo - paveFrom) * (2 * Street.HALF_WIDTH + 1);

		return settlement.addBuilding(blueprint, Math.min(x0, x1), Math.min(z0, z1), rotation,
				Math.abs(x1 - x0) + 1, Math.abs(z1 - z0) + 1, streetIndex, paveFrom, paveTo,
				ConstructionPlan.count(blueprint, paveCells));
	}

	/**
	 * Adds one street: out from the Town Center, or branching off an existing street at a right
	 * angle. Candidates are tried in random order and the first that runs over usable land wins.
	 */
	private static boolean growStreet(Settlement settlement, IdGenerator random) {
		if (settlement.streets().size() >= MAX_STREETS) {
			return false;
		}
		List<Street> candidates = new ArrayList<>();
		for (int[] direction : DIRECTIONS) {
			candidates.add(new Street(settlement.anchorX() + direction[0] * 2, settlement.anchorZ() + direction[1] * 2,
					direction[0], direction[1], STREET_LENGTH, 0));
		}
		for (Street street : settlement.streets()) {
			for (int along : BRANCH_POINTS) {
				for (int side = -1; side <= 1; side += 2) {
					candidates.add(new Street(street.cellX(along, side * 2), street.cellZ(along, side * 2),
							-street.dz() * side, street.dx() * side, STREET_LENGTH, 0));
				}
			}
		}
		while (!candidates.isEmpty()) {
			Street candidate = candidates.remove(random.nextInt(candidates.size()));
			if (isUsable(settlement, candidate)) {
				settlement.addStreet(candidate);
				return true;
			}
		}
		return false;
	}

	/** A street is worth laying if most of it, and a fair part of the land beside it, can be built on. */
	private static boolean isUsable(Settlement settlement, Street candidate) {
		for (Street street : settlement.streets()) {
			if (street.x() == candidate.x() && street.z() == candidate.z() && street.dx() == candidate.dx() && street.dz() == candidate.dz()) {
				return false;
			}
		}
		int roadCells = 0;
		int roadGood = 0;
		int sideCells = 0;
		int sideGood = 0;
		for (int along = 0; along < candidate.length(); along++) {
			for (int across = -9; across <= 9; across++) {
				int x = candidate.cellX(along, across);
				int z = candidate.cellZ(along, across);
				boolean good = settlement.plots().isBuildable(x, z) && isFree(settlement, x, z, candidate);
				if (Math.abs(across) <= Street.HALF_WIDTH) {
					if (!settlement.withinWalls(x, z)) {
						return false; // streets stop at the wall
					}
					// A street may brush past what stands or is planned, never run through it.
					for (Building building : settlement.buildings()) {
						if (building.contains(x, z)) {
							return false;
						}
					}
					roadCells++;
					// The street itself may start beside the Town Center or another street.
					if (good || (along < 4 && settlement.plots().isBuildable(x, z))) {
						roadGood++;
					}
				} else if (Math.abs(across) >= Street.LOT_OFFSET) {
					sideCells++;
					if (good) {
						sideGood++;
					}
				}
			}
		}
		return roadGood * 10 >= roadCells * 7 && sideGood * 10 >= sideCells * 4;
	}
}
