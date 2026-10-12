package com.sunnatbek.villagerciv.build;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapedWallTest {
	private final BlueprintLibrary library = BlueprintLibrary.builtIn();

	/** Marks every cell a wall's pieces fill: 1 for wall, 2 for a gate or a tower. */
	private int[][] draw(ShapedWall.Shape shape, int radius, ShapedWall.Style style) {
		int size = 2 * radius + 40;
		int[][] grid = new int[size][size];
		for (WallPiece piece : ShapedWall.plan(0, 0, shape, radius, style)) {
			Blueprint design = library.get(piece.blueprint()).orElse(null);
			assertNotNull(design, piece.blueprint());
			if (!ShapedWall.isTile(piece.blueprint())) {
				for (int x = 0; x < piece.sizeX(); x++) {
					for (int z = 0; z < piece.sizeZ(); z++) {
						grid[piece.z() + z + size / 2][piece.x() + x + size / 2] = 2;
					}
				}
				continue;
			}
			for (int x = 0; x < design.width(); x++) {
				for (int z = 0; z < design.depth(); z++) {
					if (design.cell(x, 1, z) != Blueprint.LEAVE) {
						assertEquals(0, grid[piece.z() + z + size / 2][piece.x() + x + size / 2], "no cell is built twice");
						grid[piece.z() + z + size / 2][piece.x() + x + size / 2] = 1;
					}
				}
			}
		}
		return grid;
	}

	/** Whether the middle can be walked to from the edge without crossing a marked cell. */
	private static boolean open(int[][] grid) {
		int size = grid.length;
		boolean[][] seen = new boolean[size][size];
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[] {0, 0});
		seen[0][0] = true;
		while (!queue.isEmpty()) {
			int[] at = queue.poll();
			if (at[0] == size / 2 && at[1] == size / 2) {
				return true;
			}
			for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int x = at[0] + step[0];
				int z = at[1] + step[1];
				if (x >= 0 && z >= 0 && x < size && z < size && !seen[z][x] && grid[z][x] == 0) {
					seen[z][x] = true;
					queue.add(new int[] {x, z});
				}
			}
		}
		return false;
	}

	@Test
	void everyShapeMakesAClosedRingWithFourGatesAndItsTowers() {
		for (ShapedWall.Style style : ShapedWall.Style.values()) {
			for (ShapedWall.Shape shape : ShapedWall.Shape.values()) {
				int radius = ShapedWall.radius(style == ShapedWall.Style.GREAT ? 2 : 1);
				List<WallPiece> pieces = ShapedWall.plan(0, 0, shape, radius, style);
				long gates = pieces.stream().filter(piece -> Fortification.isGate(piece.blueprint())).count();
				long towers = pieces.stream().filter(piece -> Fortification.isTower(piece.blueprint())).count();
				assertEquals(4, gates, shape + " " + style);
				assertTrue(towers >= 4, shape + " " + style + ": " + towers + " towers");
				assertFalse(open(draw(shape, radius, style)), shape + " " + style + " keeps the outside out");
				assertTrue(ShapedWall.inside(shape, radius, 0, 0) && !ShapedWall.inside(shape, radius, radius + 2, 0));
				System.out.println("SHAPE " + style + " " + shape + " radius " + radius + ": " + pieces.size() + " pieces, " + towers + " towers");
			}
		}
	}

	@Test
	void aSmallOneOfEachShapeToLookAt() {
		for (ShapedWall.Shape shape : ShapedWall.Shape.values()) {
			int[][] grid = draw(shape, 30, ShapedWall.Style.GREAT);
			StringBuilder picture = new StringBuilder("SHAPE " + shape + "\n");
			for (int z = 14; z < grid.length - 14; z += 2) {
				for (int x = 14; x < grid.length - 14; x++) {
					picture.append(grid[z][x] == 2 || grid[z + 1][x] == 2 ? '#' : grid[z][x] == 1 || grid[z + 1][x] == 1 ? 'o' : ' ');
				}
				picture.append('\n');
			}
			System.out.println(picture);
		}
	}
}
