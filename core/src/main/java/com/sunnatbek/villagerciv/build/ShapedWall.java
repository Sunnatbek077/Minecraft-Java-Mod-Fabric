package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.Commodity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A wall that is not a square: five, six or eight sided, or round; and the great wall of a city,
 * four blocks thick with a walk along its top, whatever its shape.
 *
 * <p>Such a wall cannot be made of straight stretches of a few fixed lengths, as the first walls
 * are. It is worked out cell by cell instead. The ground the wall encloses is a shape; the wall
 * is the outermost band of that shape, so many cells deep. The band is cut into square tiles, and
 * each tile is a building like any other, with a design drawn up for it alone from the shape, its
 * size and where the tile lies. Its id says all of that, so the design can be drawn up again
 * whenever it is asked for and nothing of it needs saving.
 *
 * <p>Whatever the shape, it is cut straight for a short way at its north, south, east and west:
 * a gate needs a straight piece of wall to stand in.
 */
public final class ShapedWall {
	/** The outline of the ground a wall encloses. */
	public enum Shape {
		SQUARE(4), PENTAGON(5), HEXAGON(6), OCTAGON(8), ROUND(0);

		private final int corners;

		Shape(int corners) {
			this.corners = corners;
		}

		/** Number of corners; none for a round wall. */
		public int corners() {
			return corners;
		}
	}

	/** How a shaped wall is built: thin, like the stone rampart of a town, or the great wall of a city. */
	public enum Style {
		RAMPART(1, Fortification.STONE_TOWER, 5, Fortification.STONE_GATE, 5, 3),
		GREAT(4, Fortification.GREAT_TOWER, 7, Fortification.GREAT_GATE, 14, 7);

		private final int thickness;
		private final String tower;
		private final int towerSize;
		private final String gate;
		private final int gateWidth;
		private final int gateDepth;

		Style(int thickness, String tower, int towerSize, String gate, int gateWidth, int gateDepth) {
			this.thickness = thickness;
			this.tower = tower;
			this.towerSize = towerSize;
			this.gate = gate;
			this.gateWidth = gateWidth;
			this.gateDepth = gateDepth;
		}

		public int thickness() {
			return thickness;
		}
	}

	public static final String PREFIX = "shapedwall.";
	static final int TILE = 12;
	/** How long the straight cut at each of the four gates is, at the least. */
	private static final int GATE_FLAT = 19;
	/** About how far apart towers stand along a side. */
	private static final int TOWER_SPACING = 45;
	private static final int STONE_PER_CELL = 8;

	private static final Map<String, int[]> CUTS = new HashMap<>();

	private ShapedWall() {
	}

	// --- the shape -------------------------------------------------------------------------

	/** Whether a cell, counted from the middle, is inside a shape of this size before its gates are cut straight. */
	private static boolean raw(Shape shape, int radius, int dx, int dz) {
		switch (shape) {
			case SQUARE:
				return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
			case ROUND:
				return (long) dx * dx + (long) dz * dz <= (long) radius * radius + radius;
			default:
				int n = shape.corners();
				// A pentagon points north and stands on a flat side; a hexagon and an octagon
				// have flat sides to north and south.
				double turn = shape == Shape.PENTAGON ? -Math.PI / 2 : shape == Shape.HEXAGON ? 0 : Math.PI / 8;
				double px = dx;
				double pz = dz;
				for (int i = 0; i < n; i++) {
					double a = turn + 2 * Math.PI * i / n;
					double b = turn + 2 * Math.PI * (i + 1) / n;
					double ax = Math.cos(a) * radius;
					double az = Math.sin(a) * radius;
					double bx = Math.cos(b) * radius;
					double bz = Math.sin(b) * radius;
					// Half a cell's grace, so that the corners themselves count as inside.
					if ((bx - ax) * (pz - az) - (bz - az) * (px - ax) < -0.5 * radius) {
						return false;
					}
				}
				return true;
		}
	}

	/** How far the shape reaches east, west, south and north once it is cut straight for its gates: {east, west, south, north}, the last two as z. */
	private static int[] cuts(Shape shape, int radius) {
		return CUTS.computeIfAbsent(shape + "/" + radius, _ -> {
			int[] cuts = new int[4];
			for (int side = 0; side < 4; side++) {
				int ux = side == 0 ? 1 : side == 1 ? -1 : 0;
				int uz = side == 2 ? 1 : side == 3 ? -1 : 0;
				int reach = radius + 1;
				while (reach > 0) {
					int width = 0;
					for (int across = -radius; across <= radius; across++) {
						if (raw(shape, radius, ux * reach + (ux == 0 ? across : 0), uz * reach + (uz == 0 ? across : 0))) {
							width++;
						}
					}
					if (width >= GATE_FLAT) {
						break;
					}
					reach--;
				}
				cuts[side] = reach;
			}
			return cuts;
		});
	}

	/** Whether a cell, counted from the middle, lies within a shaped wall of this size: on the wall or inside it. */
	public static boolean inside(Shape shape, int radius, int dx, int dz) {
		if (radius <= 0) {
			return false;
		}
		int[] cuts = cuts(shape, radius);
		return dx <= cuts[0] && dx >= -cuts[1] && dz <= cuts[2] && dz >= -cuts[3] && raw(shape, radius, dx, dz);
	}

	/**
	 * How deep into the shape a cell lies: 1 for the outermost cells, 2 for the next, and so on up
	 * to {@code most}; 0 if it is outside, and {@code most + 1} if it is further in than that.
	 */
	static int depth(Shape shape, int radius, int dx, int dz, int most) {
		if (!inside(shape, radius, dx, dz)) {
			return 0;
		}
		for (int k = 1; k <= most; k++) {
			for (int a = -k; a <= k; a++) {
				if (!inside(shape, radius, dx + a, dz - k) || !inside(shape, radius, dx + a, dz + k)
						|| !inside(shape, radius, dx - k, dz + a) || !inside(shape, radius, dx + k, dz + a)) {
					return k;
				}
			}
		}
		return most + 1;
	}

	// --- gates and towers ------------------------------------------------------------------

	/** Where the gates and towers of a shaped wall stand, counted from the middle: gates first. */
	private static List<WallPiece> strongPoints(Shape shape, int radius, Style style) {
		List<WallPiece> points = new ArrayList<>();
		int[] cuts = cuts(shape, radius);
		int t = style.thickness;
		int w = style.gateWidth;
		int d = style.gateDepth;
		// The gate sits across the wall, any depth beyond the wall's own shared between its two faces.
		int inside = (d - t) / 2;
		int outside = d - t - inside;
		// Sides in the order south, west, north, east: the rotation that turns a design's front outwards.
		points.add(new WallPiece(style.gate, -(w / 2), cuts[2] - (t - 1) - inside, 0, w, d));
		points.add(new WallPiece(style.gate, -cuts[1] - outside, -(w / 2), 1, d, w));
		points.add(new WallPiece(style.gate, -(w / 2), -cuts[3] - outside, 2, w, d));
		points.add(new WallPiece(style.gate, cuts[0] - (t - 1) - inside, -(w / 2), 3, d, w));

		List<double[]> angles = new ArrayList<>();
		if (shape == Shape.ROUND) {
			int count = Math.max(8, (int) Math.round(2 * Math.PI * radius / TOWER_SPACING / 4.0) * 4);
			for (int i = 0; i < count; i++) {
				angles.add(new double[] {Math.cos(2 * Math.PI * (i + 0.5) / count), Math.sin(2 * Math.PI * (i + 0.5) / count)});
			}
		} else {
			int n = shape.corners();
			double turn = shape == Shape.SQUARE ? Math.PI / 4 : shape == Shape.PENTAGON ? -Math.PI / 2 : shape == Shape.HEXAGON ? 0 : Math.PI / 8;
			double reach = shape == Shape.SQUARE ? radius * Math.sqrt(2) : radius;
			double side = 2 * reach * Math.sin(Math.PI / n);
			int parts = Math.max(1, (int) Math.round(side / TOWER_SPACING));
			for (int i = 0; i < n; i++) {
				double ax = Math.cos(turn + 2 * Math.PI * i / n);
				double az = Math.sin(turn + 2 * Math.PI * i / n);
				double bx = Math.cos(turn + 2 * Math.PI * (i + 1) / n);
				double bz = Math.sin(turn + 2 * Math.PI * (i + 1) / n);
				for (int part = 0; part < parts; part++) {
					double f = (double) part / parts;
					angles.add(new double[] {ax + (bx - ax) * f, az + (bz - az) * f});
				}
			}
		}
		int half = style.towerSize / 2;
		for (double[] angle : angles) {
			double length = Math.hypot(angle[0], angle[1]);
			double ux = angle[0] / length;
			double uz = angle[1] / length;
			// Out along the ray to the last cell that is inside, then back in to the middle of the wall.
			int step = (int) (radius * 1.5);
			while (step > 0 && !inside(shape, radius, (int) Math.round(ux * step), (int) Math.round(uz * step))) {
				step--;
			}
			double in = step - (t - 1) / 2.0;
			int cx = (int) Math.round(ux * in);
			int cz = (int) Math.round(uz * in);
			WallPiece tower = new WallPiece(style.tower, cx - half, cz - half, 0, style.towerSize, style.towerSize);
			boolean clear = true;
			for (WallPiece other : points) {
				clear &= tower.x() > other.x() + other.sizeX() + 1 || tower.x() + tower.sizeX() + 1 < other.x()
						|| tower.z() > other.z() + other.sizeZ() + 1 || tower.z() + tower.sizeZ() + 1 < other.z();
			}
			if (clear) {
				points.add(tower);
			}
		}
		return points;
	}

	private static boolean covered(List<WallPiece> points, int dx, int dz) {
		for (WallPiece point : points) {
			if (dx >= point.x() && dx < point.x() + point.sizeX() && dz >= point.z() && dz < point.z() + point.sizeZ()) {
				return true;
			}
		}
		return false;
	}

	// --- the plan --------------------------------------------------------------------------

	/** Half the side of the square of ground a ring of this stage takes in. */
	public static int radius(int stage) {
		return Fortification.OUTER_RADIUS * Math.max(1, stage);
	}

	/**
	 * The pieces of a shaped wall round a point, in the order they should be built: gates, then
	 * towers, then the wall itself, tile by tile round the ring.
	 */
	public static List<WallPiece> plan(int anchorX, int anchorZ, Shape shape, int radius, Style style) {
		List<WallPiece> points = strongPoints(shape, radius, style);
		List<WallPiece> all = new ArrayList<>();
		for (WallPiece point : points) {
			all.add(new WallPiece(point.blueprint(), anchorX + point.x(), anchorZ + point.z(), point.rotation(), point.sizeX(), point.sizeZ()));
		}
		List<WallPiece> tiles = new ArrayList<>();
		for (int oz = -radius - 1; oz <= radius; oz += TILE) {
			for (int ox = -radius - 1; ox <= radius; ox += TILE) {
				if (hasWall(shape, radius, style, points, ox, oz)) {
					tiles.add(new WallPiece(id(style, shape, radius, ox, oz), anchorX + ox, anchorZ + oz, 0, TILE, TILE));
				}
			}
		}
		// Round the ring rather than row by row, so the wall is seen to grow along its length.
		tiles.sort(java.util.Comparator.comparingDouble(tile -> Math.atan2(tile.z() + TILE / 2.0 - anchorZ, tile.x() + TILE / 2.0 - anchorX)));
		all.addAll(tiles);
		return all;
	}

	private static boolean hasWall(Shape shape, int radius, Style style, List<WallPiece> points, int ox, int oz) {
		for (int z = 0; z < TILE; z++) {
			for (int x = 0; x < TILE; x++) {
				int depth = depth(shape, radius, ox + x, oz + z, style.thickness);
				if (depth >= 1 && depth <= style.thickness && !covered(points, ox + x, oz + z)) {
					return true;
				}
			}
		}
		return false;
	}

	static String id(Style style, Shape shape, int radius, int ox, int oz) {
		return PREFIX + style.name().toLowerCase(java.util.Locale.ROOT) + "." + shape.name().toLowerCase(java.util.Locale.ROOT)
				+ "." + radius + "." + ox + "." + oz;
	}

	public static boolean isTile(String id) {
		return id.startsWith(PREFIX);
	}

	/**
	 * Whether a cell of a tile is wall. A tile is a square of ground with the wall running
	 * through it; the rest of the square is ordinary ground, to be levelled like any other.
	 *
	 * @param x the cell's place in the tile, from its north-west corner
	 */
	public static boolean isWallCell(String id, int x, int z) {
		try {
			String[] parts = id.substring(PREFIX.length()).split("\\.");
			Style style = Style.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT));
			Shape shape = Shape.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
			int depth = depth(shape, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) + x, Integer.parseInt(parts[4]) + z, style.thickness);
			return depth >= 1 && depth <= style.thickness;
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** Whether a design belongs to the great wall of a city: its tiles, its towers and its gates. */
	public static boolean isGreat(String id) {
		return id.startsWith(PREFIX + "great.") || id.equals(Fortification.GREAT_TOWER) || id.equals(Fortification.GREAT_GATE);
	}

	// --- the design of one tile ------------------------------------------------------------

	/** The design for a tile's id, or null if the id does not name one. */
	static Blueprint forId(String id) {
		if (!isTile(id)) {
			return null;
		}
		try {
			String[] parts = id.substring(PREFIX.length()).split("\\.");
			Style style = Style.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT));
			Shape shape = Shape.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
			int radius = Integer.parseInt(parts[2]);
			int ox = Integer.parseInt(parts[3]);
			int oz = Integer.parseInt(parts[4]);
			return radius < 8 || radius > 512 ? null : tile(id, style, shape, radius, ox, oz);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Blueprint tile(String id, Style style, Shape shape, int radius, int ox, int oz) {
		List<WallPiece> points = strongPoints(shape, radius, style);
		boolean great = style == Style.GREAT;
		int height = great ? 10 : 7;
		char[][][] cells = new char[height][TILE][TILE];
		for (char[][] layer : cells) {
			for (char[] row : layer) {
				java.util.Arrays.fill(row, Blueprint.LEAVE);
			}
		}
		int count = 0;
		for (int z = 0; z < TILE; z++) {
			for (int x = 0; x < TILE; x++) {
				int dx = ox + x;
				int dz = oz + z;
				int depth = depth(shape, radius, dx, dz, style.thickness);
				if (depth < 1 || depth > style.thickness || covered(points, dx, dz)) {
					continue;
				}
				count++;
				// A pattern that runs evenly along the wall whichever way it turns.
				int along = Math.floorMod(dx + dz, 2);
				int mark = Math.floorMod(dx * 7 + dz * 13, 8);
				int wear = Math.floorMod(dx * 31 + dz * 17 + (dx ^ dz), 11);
				if (great) {
					greatColumn(cells, x, z, depth, along, mark, wear);
				} else {
					cells[1][z][x] = 'M';
					cells[2][z][x] = wear == 0 ? 'K' : 'S';
					cells[3][z][x] = 'S';
					cells[4][z][x] = 'C';
					cells[5][z][x] = 'S';
					cells[6][z][x] = along == 0 ? 'S' : 'w';
				}
			}
		}
		List<List<String>> layers = new ArrayList<>();
		for (char[][] layer : cells) {
			List<String> rows = new ArrayList<>();
			for (char[] row : layer) {
				rows.add(new String(row));
			}
			layers.add(List.copyOf(rows));
		}
		Map<Character, String> palette = new LinkedHashMap<>();
		palette.put('M', "minecraft:mossy_stone_bricks");
		palette.put('S', "minecraft:stone_bricks");
		palette.put('K', "minecraft:cracked_stone_bricks");
		palette.put('C', "minecraft:chiseled_stone_bricks");
		palette.put('w', "minecraft:stone_brick_wall");
		String late = "";
		if (great) {
			palette.put('D', "minecraft:polished_deepslate");
			palette.put('B', "minecraft:deepslate_bricks");
			palette.put('T', "minecraft:deepslate_tiles");
			palette.put('F', "minecraft:cobbled_deepslate");
			palette.put('A', "minecraft:polished_andesite");
			palette.put('G', "minecraft:smooth_stone");
			palette.put('I', "minecraft:iron_bars");
			palette.put('l', "minecraft:lantern[hanging=false]");
			late = "l";
		}
		return new Blueprint(id, great ? "Great Wall" : "Rampart", Blueprint.Category.DEFENSE, TILE, height, TILE, List.copyOf(layers),
				palette, late, 0, Map.of(Commodity.STONE, Math.max(1, count) * (great ? STONE_PER_CELL : STONE_PER_CELL / 2)), Map.of(), 0);
	}

	/**
	 * One column of the great wall. From the outside in: a face of stone brick on a dark footing,
	 * with pilasters of deepslate, arrow slits, a moulded course under a parapet of merlons; two
	 * columns of fill under a paved walk; and an inner face with a low parapet and lanterns.
	 */
	private static void greatColumn(char[][][] cells, int x, int z, int depth, int along, int mark, int wear) {
		boolean pilaster = mark == 0;
		if (depth == 1) {
			cells[1][z][x] = 'D';
			cells[2][z][x] = pilaster ? 'B' : wear == 0 ? 'M' : 'S';
			cells[3][z][x] = pilaster ? 'B' : wear == 1 ? 'K' : 'S';
			cells[4][z][x] = pilaster ? 'B' : mark == 4 ? 'I' : 'S';
			cells[5][z][x] = pilaster ? 'B' : 'S';
			cells[6][z][x] = pilaster ? 'D' : 'C';
			cells[7][z][x] = pilaster ? 'B' : 'S';
			// Merlons and gaps by turns; a pilaster carries on up into a taller one, with a light.
			cells[8][z][x] = pilaster ? 'B' : along == 0 ? 'S' : Blueprint.CLEAR;
			cells[9][z][x] = pilaster ? 'l' : Blueprint.CLEAR;
		} else if (depth == 4) {
			cells[1][z][x] = 'D';
			cells[2][z][x] = wear == 0 ? 'M' : 'S';
			cells[3][z][x] = 'S';
			cells[4][z][x] = wear == 2 ? 'K' : 'S';
			cells[5][z][x] = 'S';
			cells[6][z][x] = 'T';
			cells[7][z][x] = 'w';
			cells[8][z][x] = mark == 2 ? 'l' : Blueprint.CLEAR;
			cells[9][z][x] = Blueprint.CLEAR;
		} else {
			cells[1][z][x] = 'F';
			cells[2][z][x] = 'F';
			cells[3][z][x] = 'F';
			cells[4][z][x] = 'F';
			cells[5][z][x] = 'F';
			// The walk: paved, with a darker stone down the middle of it.
			cells[6][z][x] = depth == 2 ? 'A' : 'G';
			cells[7][z][x] = Blueprint.CLEAR;
			cells[8][z][x] = Blueprint.CLEAR;
			cells[9][z][x] = Blueprint.CLEAR;
		}
	}
}
