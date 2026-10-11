package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.Commodity;

import java.util.List;
import java.util.Map;

/**
 * An immutable building design: a block of cells, layer by layer, plus what the building is for
 * and what it costs. Blueprints are written as text so they can be authored, read and reviewed
 * like code.
 *
 * <p>Layout conventions: layers run bottom to top, and layer 0 replaces the ground block itself
 * (the floor sits flush with the land). Within a layer, rows run north to south and columns west
 * to east. The front of every blueprint is its south side, the last row; a building is rotated
 * when placed so that this side faces its street.
 *
 * <p>Cells: a space leaves the world as it is, a dot clears the cell to air, and any other
 * character is looked up in the palette.
 *
 * @param storage palette keys of the chests that hold a settlement's stock, and what each holds
 * @param minStage how far along a settlement has to be before it builds this of its own accord,
 *                 as the ordinal of its stage: 0 for anything a camp would build
 * @param late palette keys placed only after everything else: things that hang from or lean on
 *             other blocks (doors, beds, lanterns), which would pop off if placed too early
 */
public record Blueprint(String id, String name, Category category, int width, int height, int depth,
		List<List<String>> layers, Map<Character, String> palette, String late, int beds, Map<Commodity, Integer> cost,
		Map<Character, Commodity> storage, int minStage) {
	public static final char LEAVE = ' ';
	public static final char CLEAR = '.';
	/** Ids of the main storehouse's designs start with this, followed by the tier: 1, 2, 3. */
	public static final String MAIN_STORE = "great_store_";
	public static final String SMITHY = "smithy";
	/** The pens animals are kept in, one kind to each. */
	public static final java.util.List<String> PENS = java.util.List.of("cattle_pen", "sheep_pen", "pig_sty", "chicken_coop");

	public static boolean isPen(String id) {
		return PENS.contains(id);
	}

	public static final String MASONS_YARD = "masons_yard";
	public static final String MARKET = "market";

	public enum Category {
		SHELTER,
		HOUSING,
		STORAGE,
		/** A place for everyone and nobody's home: a well, a tavern, a chapel. Good for the settlement's spirits. */
		PUBLIC,
		/** A place of one trade, such as the smithy: built once, and worked by those who hold that job. */
		WORKSHOP,
		/** Walls, gates and towers: built from the defence plan, not from the list of needs. */
		DEFENSE
	}

	/** A cell of the blueprint, in its own orientation. */
	public record Cell(int x, int y, int z) {
	}

	/** Where the head of each bed is, in the order the beds are handed out. */
	public List<Cell> bedHeads() {
		List<Cell> heads = new java.util.ArrayList<>();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < depth; z++) {
				for (int x = 0; x < width; x++) {
					char cell = cell(x, y, z);
					if (cell != LEAVE && cell != CLEAR) {
						String state = palette.get(cell);
						if (state.contains("_bed[") && state.contains("part=head")) {
							heads.add(new Cell(x, y, z));
						}
					}
				}
			}
		}
		return heads;
	}

	/**
	 * A number that changes whenever the design does. A building remembers the one it was built
	 * from, which is how a later change to the design is noticed and the building brought up to date.
	 */
	public int fingerprint() {
		int hash = 31 * layers.hashCode() + palette.hashCode();
		return hash == 0 ? 1 : hash;
	}

	/** The first cell holding a block whose state starts with this text, or null: the anvil of a smithy, say. */
	public Cell cellOf(String statePrefix) {
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < depth; z++) {
				for (int x = 0; x < width; x++) {
					char cell = cell(x, y, z);
					if (cell != LEAVE && cell != CLEAR && palette.get(cell).startsWith(statePrefix)) {
						return new Cell(x, y, z);
					}
				}
			}
		}
		return null;
	}

	/** A chest of a storehouse and the commodity kept in it. */
	public record StoreCell(Cell cell, Commodity commodity) {
	}

	/** The chests that show a settlement's stock, section by section. */
	public List<StoreCell> storeCells() {
		List<StoreCell> cells = new java.util.ArrayList<>();
		if (storage.isEmpty()) {
			return cells;
		}
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < depth; z++) {
				for (int x = 0; x < width; x++) {
					Commodity commodity = storage.get(cell(x, y, z));
					if (commodity != null) {
						cells.add(new StoreCell(new Cell(x, y, z), commodity));
					}
				}
			}
		}
		return cells;
	}

	/** Where the chests are: one stands by each bed that has room for it. */
	public List<Cell> chests() {
		List<Cell> chests = new java.util.ArrayList<>();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < depth; z++) {
				for (int x = 0; x < width; x++) {
					char cell = cell(x, y, z);
					if (cell != LEAVE && cell != CLEAR && palette.get(cell).startsWith("minecraft:chest")) {
						chests.add(new Cell(x, y, z));
					}
				}
			}
		}
		return chests;
	}

	public char cell(int x, int y, int z) {
		return layers.get(y).get(z).charAt(x);
	}

	public boolean isLate(char key) {
		return late.indexOf(key) >= 0;
	}

	public int cost(Commodity commodity) {
		return cost.getOrDefault(commodity, 0);
	}
}
