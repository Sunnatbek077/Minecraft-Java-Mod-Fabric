package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.WorkSite;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a settlement's wall: a square ring around everything it has built and works, with a
 * gate in the middle of each side (where the streets from the Town Center lead) and a watchtower
 * on each corner.
 *
 * <p>The ring is cut into short pieces. Each piece is levelled and built on its own, so on
 * sloping ground the wall climbs in steps instead of floating or burying itself. Gates and
 * towers come first: a wall that is only half up is already some use if its strong points stand.
 */
public final class Fortification {
	public static final String GATE = "palisade_gate";
	public static final String TOWER = "watchtower";
	/** The gate and tower of the stone ring: the same shapes, in stone. */
	public static final String STONE_GATE = "stone_gate";
	public static final String STONE_TOWER = "stone_tower";
	/** The gatehouse and tower of a city's great wall. */
	public static final String GREAT_GATE = "great_gate";
	public static final String GREAT_TOWER = "great_tower";
	/** Half the side of the second ring: it takes in the whole of the enlarged survey but for a margin. */
	public static final int OUTER_RADIUS = 88;
	private static final int MARGIN = 5;
	/**
	 * The ring is never drawn tight around a young settlement: what is inside the wall is all the
	 * room it will have to grow in.
	 */
	private static final int MIN_RADIUS = 44;
	private static final int MAX_RADIUS = 54;
	private static final int TOWER_HALF = 2;
	private static final int GATE_HALF = 2;

	private Fortification() {
	}

	/** Half the side of the ring: far enough out to take in every building, street and work site. */
	static int radius(Settlement settlement) {
		int reach = 0;
		for (Building building : settlement.buildings()) {
			reach = Math.max(reach, Math.max(
					Math.max(Math.abs(building.x() - settlement.anchorX()), Math.abs(building.x() + building.sizeX() - settlement.anchorX())),
					Math.max(Math.abs(building.z() - settlement.anchorZ()), Math.abs(building.z() + building.sizeZ() - settlement.anchorZ()))));
		}
		for (WorkSite site : settlement.sites()) {
			reach = Math.max(reach, Math.max(Math.abs(site.x() - settlement.anchorX()), Math.abs(site.z() - settlement.anchorZ())) + WorkSite.HALF_WIDTH);
		}
		for (Street street : settlement.streets()) {
			int end = street.paved();
			reach = Math.max(reach, Math.max(Math.abs(street.cellX(end, 0) - settlement.anchorX()), Math.abs(street.cellZ(end, 0) - settlement.anchorZ())));
		}
		return Math.clamp(reach + MARGIN, MIN_RADIUS, MAX_RADIUS);
	}

	/** The pieces of the ring, in the order they should be built. */
	public static List<WallPiece> plan(Settlement settlement, BlueprintLibrary library) {
		return ring(settlement, library, radius(settlement), false);
	}

	/**
	 * The second ring: stone, and far enough out to hold a town. Besides its corner towers it has
	 * towers along its sides, sixteen in all. Once it stands, the first ring inside it is taken down.
	 */
	public static List<WallPiece> planOuter(Settlement settlement, BlueprintLibrary library) {
		return ring(settlement, library, OUTER_RADIUS, true);
	}

	/**
	 * Where a side of the stone ring is broken, measured along it from its middle: by its gate
	 * (0) and by its towers. The south and north sides carry four towers each, the west and east
	 * two, which with the four corners makes sixteen.
	 */
	private static int[] stops(int side) {
		return side % 2 == 0 ? new int[] {-59, -29, 0, 29, 59} : new int[] {-44, 0, 44};
	}

	/**
	 * The towers along the sides of the stone ring. A ring planned before it had any is given
	 * them afterwards: each is built over the stretch of wall that stands there.
	 */
	public static List<WallPiece> sideTowers(Settlement settlement, BlueprintLibrary library) {
		Blueprint tower = library.get(STONE_TOWER).orElseThrow();
		List<WallPiece> towers = new ArrayList<>();
		for (int side = 0; side < 4; side++) {
			for (int stop : stops(side)) {
				if (stop != 0) {
					towers.add(piece(settlement.anchorX(), settlement.anchorZ(), OUTER_RADIUS, side, stop - TOWER_HALF,
							tower.width(), tower.depth(), STONE_TOWER));
				}
			}
		}
		return towers;
	}

	/**
	 * Which of a settlement's rings a piece of wall belongs to: 0 for the first, of wood; 1 for
	 * the stone ring of a town; 2 for the great wall of a city.
	 */
	public static int ringOf(String blueprint) {
		return isFirstRing(blueprint) ? 0 : ShapedWall.isGreat(blueprint) ? 2 : 1;
	}

	/** Whether a piece belongs to the first ring, of wood. */
	public static boolean isFirstRing(String blueprint) {
		return blueprint.equals(GATE) || blueprint.equals(TOWER) || blueprint.startsWith(WallDesigns.PALISADE_PREFIX);
	}

	public static boolean isGate(String blueprint) {
		return blueprint.equals(GATE) || blueprint.equals(STONE_GATE) || blueprint.equals(GREAT_GATE);
	}

	public static boolean isTower(String blueprint) {
		return blueprint.equals(TOWER) || blueprint.equals(STONE_TOWER) || blueprint.equals(GREAT_TOWER);
	}

	private static List<WallPiece> ring(Settlement settlement, BlueprintLibrary library, int radius, boolean stone) {
		int ax = settlement.anchorX();
		int az = settlement.anchorZ();
		String gateId = stone ? STONE_GATE : GATE;
		String towerId = stone ? STONE_TOWER : TOWER;
		Blueprint gate = library.get(gateId).orElseThrow();
		Blueprint tower = library.get(towerId).orElseThrow();

		List<WallPiece> gates = new ArrayList<>();
		List<WallPiece> towers = new ArrayList<>();
		List<WallPiece> walls = new ArrayList<>();
		// Sides in the order south, west, north, east: the rotation that turns a design's front outwards.
		for (int side = 0; side < 4; side++) {
			gates.add(piece(ax, az, radius, side, -GATE_HALF, gate.width(), gate.depth(), gateId));
			// Wall runs from just past the corner tower to the gate, on either hand, broken by
			// whatever towers stand along the side.
			int from = -radius + TOWER_HALF + 1;
			for (int stop : stone ? stops(side) : new int[] {0}) {
				if (stop != 0) {
					towers.add(piece(ax, az, radius, side, stop - TOWER_HALF, tower.width(), tower.depth(), towerId));
				}
				addRun(walls, ax, az, radius, side, from, stop - TOWER_HALF - 1, stone);
				from = stop + TOWER_HALF + 1;
			}
			addRun(walls, ax, az, radius, side, from, radius - TOWER_HALF - 1, stone);
		}
		for (int cornerX = -1; cornerX <= 1; cornerX += 2) {
			for (int cornerZ = -1; cornerZ <= 1; cornerZ += 2) {
				towers.add(new WallPiece(towerId, ax + cornerX * radius - TOWER_HALF, az + cornerZ * radius - TOWER_HALF, 0,
						tower.width(), tower.depth()));
			}
		}
		List<WallPiece> all = new ArrayList<>(gates);
		all.addAll(towers);
		all.addAll(walls);
		return all;
	}

	/** Fills the stretch from {@code from} to {@code to} (inclusive, along the side) with palisade pieces. */
	private static void addRun(List<WallPiece> walls, int ax, int az, int radius, int side, int from, int to, boolean stone) {
		for (int start = from; start <= to; ) {
			int length = Math.min(WallDesigns.MAX_LENGTH, to - start + 1);
			walls.add(piece(ax, az, radius, side, start, length, 1, stone ? WallDesigns.rampartId(length) : WallDesigns.palisadeId(length)));
			start += length;
		}
	}

	/**
	 * A piece on one side of the ring.
	 *
	 * @param along where it starts, measured along the side from the side's middle
	 * @param width its extent along the side; {@code depth} is its extent across the ring line
	 */
	private static WallPiece piece(int ax, int az, int radius, int side, int along, int width, int depth, String blueprint) {
		int inwards = (depth - 1) / 2;
		return switch (side) {
			case 0 -> new WallPiece(blueprint, ax + along, az + radius - inwards, 0, width, depth);
			case 1 -> new WallPiece(blueprint, ax - radius - (depth - 1 - inwards), az + along, 1, depth, width);
			case 2 -> new WallPiece(blueprint, ax - along - width + 1, az - radius - (depth - 1 - inwards), 2, width, depth);
			default -> new WallPiece(blueprint, ax + radius - inwards, az - along - width + 1, 3, depth, width);
		};
	}
}
