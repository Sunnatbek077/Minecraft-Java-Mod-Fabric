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

	/** The second ring: stone, and far enough out to hold a town. The first stays standing inside it. */
	public static List<WallPiece> planOuter(Settlement settlement, BlueprintLibrary library) {
		return ring(settlement, library, OUTER_RADIUS, true);
	}

	public static boolean isGate(String blueprint) {
		return blueprint.equals(GATE) || blueprint.equals(STONE_GATE);
	}

	public static boolean isTower(String blueprint) {
		return blueprint.equals(TOWER) || blueprint.equals(STONE_TOWER);
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
			// Wall runs from just past the corner tower to the gate, on either hand.
			addRun(walls, ax, az, radius, side, -radius + TOWER_HALF + 1, -GATE_HALF - 1, stone);
			addRun(walls, ax, az, radius, side, GATE_HALF + 1, radius - TOWER_HALF - 1, stone);
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
