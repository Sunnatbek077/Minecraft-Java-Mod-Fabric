package com.sunnatbek.villagerciv.build;

import com.sunnatbek.villagerciv.sim.Commodity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wall designs made to measure. A perimeter never divides evenly into fixed pieces, so a stretch
 * of wall is not drawn by hand: it is generated for whatever length is needed. To everything
 * downstream the result is an ordinary {@link Blueprint}.
 */
public final class WallDesigns {
	public static final String PALISADE_PREFIX = "palisade_";
	public static final int MAX_LENGTH = 8;
	/** Wood per block of wall length. */
	private static final int WOOD_PER_BLOCK = 6;

	private WallDesigns() {
	}

	/** Id of the palisade design of a given length. */
	public static String palisadeId(int length) {
		return PALISADE_PREFIX + length;
	}

	/** The design for a generated id, or null if the id does not name one. */
	static Blueprint forId(String id) {
		if (!id.startsWith(PALISADE_PREFIX)) {
			return null;
		}
		try {
			int length = Integer.parseInt(id.substring(PALISADE_PREFIX.length()));
			return length >= 1 && length <= MAX_LENGTH ? palisade(length) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * A palisade: upright logs three high, too tall to climb or jump, topped by a row of stakes
	 * and log ends in turn.
	 */
	private static Blueprint palisade(int length) {
		String ground = " ".repeat(length);
		String logs = "L".repeat(length);
		StringBuilder top = new StringBuilder();
		for (int i = 0; i < length; i++) {
			top.append(i % 2 == 0 ? 'F' : 'T');
		}
		List<List<String>> layers = new ArrayList<>();
		layers.add(List.of(ground));
		layers.add(List.of(logs));
		layers.add(List.of(logs));
		layers.add(List.of(logs));
		layers.add(List.of(top.toString()));
		return new Blueprint(palisadeId(length), "Palisade", Blueprint.Category.DEFENSE, length, layers.size(), 1, List.copyOf(layers),
				Map.of('L', "minecraft:spruce_log[axis=y]", 'T', "minecraft:stripped_spruce_log[axis=y]", 'F', "minecraft:spruce_fence"),
				"", 0, Map.of(Commodity.WOOD, length * WOOD_PER_BLOCK), Map.of());
	}
}
