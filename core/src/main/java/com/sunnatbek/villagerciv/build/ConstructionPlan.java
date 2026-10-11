package com.sunnatbek.villagerciv.build;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered list of steps that turns a lot into a building. It is a pure function of the
 * blueprint, the building's position and its street, so it never has to be saved: after a
 * restart the same list comes out again and work resumes at the saved cursor.
 *
 * <p>Order: pave the street up to the lot, level the whole footprint (nothing is built on a
 * slope), then the blocks from the bottom layer up, and last of all the fittings that hang from
 * or lean on other blocks.
 */
public final class ConstructionPlan {
	public enum Kind {
		PAVE,
		LEVEL,
		PLACE
	}

	/**
	 * @param dy    height above the lot's ground block (0 is the ground block itself)
	 * @param state for {@link Kind#PLACE}: the block state as written in the palette, in the
	 *              blueprint's own orientation, or null to clear the cell to air
	 */
	public record Step(Kind kind, int x, int dy, int z, String state) {
	}

	private ConstructionPlan() {
	}

	public static List<Step> of(Building building, Blueprint blueprint, Street street) {
		List<Step> steps = new ArrayList<>();
		if (street != null) {
			for (int along = building.paveFrom(); along < building.paveTo(); along++) {
				for (int across = -Street.HALF_WIDTH; across <= Street.HALF_WIDTH; across++) {
					steps.add(new Step(Kind.PAVE, street.cellX(along, across), 0, street.cellZ(along, across), null));
				}
			}
		}
		for (int dz = 0; dz < building.sizeZ(); dz++) {
			for (int dx = 0; dx < building.sizeX(); dx++) {
				steps.add(new Step(Kind.LEVEL, building.x() + dx, 0, building.z() + dz, null));
			}
		}
		addBlocks(steps, building, blueprint, false);
		addBlocks(steps, building, blueprint, true);
		return steps;
	}

	/** Number of steps {@link #of} yields, without building the list. */
	public static int count(Blueprint blueprint, int paveCells) {
		int blocks = 0;
		for (int y = 0; y < blueprint.height(); y++) {
			for (int z = 0; z < blueprint.depth(); z++) {
				for (int x = 0; x < blueprint.width(); x++) {
					if (blueprint.cell(x, y, z) != Blueprint.LEAVE) {
						blocks++;
					}
				}
			}
		}
		return paveCells + blueprint.width() * blueprint.depth() + blocks;
	}

	private static void addBlocks(List<Step> steps, Building building, Blueprint blueprint, boolean late) {
		for (int y = 0; y < blueprint.height(); y++) {
			for (int z = 0; z < blueprint.depth(); z++) {
				for (int x = 0; x < blueprint.width(); x++) {
					char cell = blueprint.cell(x, y, z);
					if (cell == Blueprint.LEAVE || (cell != Blueprint.CLEAR && blueprint.isLate(cell)) != late) {
						continue;
					}
					if (cell == Blueprint.CLEAR && late) {
						continue;
					}
					steps.add(new Step(Kind.PLACE, building.x() + rotatedX(blueprint, building.rotation(), x, z), y,
							building.z() + rotatedZ(blueprint, building.rotation(), x, z),
							cell == Blueprint.CLEAR ? null : blueprint.palette().get(cell)));
				}
			}
		}
	}

	/** Footprint offset of a blueprint cell after {@code rotation} quarter turns clockwise. */
	public static int rotatedX(Blueprint blueprint, int rotation, int x, int z) {
		return switch (rotation & 3) {
			case 0 -> x;
			case 1 -> blueprint.depth() - 1 - z;
			case 2 -> blueprint.width() - 1 - x;
			default -> z;
		};
	}

	public static int rotatedZ(Blueprint blueprint, int rotation, int x, int z) {
		return switch (rotation & 3) {
			case 0 -> z;
			case 1 -> x;
			case 2 -> blueprint.depth() - 1 - z;
			default -> blueprint.width() - 1 - x;
		};
	}
}
