import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turns the castle schematic (Sponge v2, from CastleForge) into the mod's own compact form: only
 * the castle itself, standing on its octagon above the plateau, without the landscape round it or
 * the rock beneath it. The town's stone platform takes the place of the plateau.
 *
 * <pre>
 * java tools/CastleConvert.java ~/Desktop/castle.schem src/main/resources/villagerciv/castle/octagon_castle.dat
 * java tools/CastleConvert.java ~/Desktop/castle.schem --survey      (only prints how the layers are filled)
 * </pre>
 *
 * Output (gzip): int magic "VCCS", int version, palette (count, UTF each), the plateau's apothem,
 * then layer by layer (y from 0, the first layer above the plateau): row count, and for each row
 * of v its runs of one block along u, as (u0, length, palette index). u and v are measured from the
 * octagon's centre, x and z the same way round as in the world.
 */
public final class CastleConvert {
	/** The castle's octagon: 330 blocks between opposite walls. */
	private static final int APOTHEM = 165;
	private static final double DIAGONAL = APOTHEM * Math.sqrt(2) + 1;
	/** Its corner towers and buttresses stand out beyond the octagon by this much. */
	private static final int OUTWORKS = 16;
	/** What lies round the castle rather than being part of it: kept inside the octagon, dropped outside. */
	private static final java.util.Set<String> LAND = java.util.Set.of("minecraft:water", "minecraft:dirt", "minecraft:grass_block",
			"minecraft:spruce_leaves", "minecraft:dark_oak_leaves", "minecraft:azalea_leaves", "minecraft:podzol", "minecraft:gravel",
			"minecraft:stone", "minecraft:moss_block", "minecraft:dark_oak_log", "minecraft:deepslate", "minecraft:tuff", "minecraft:dirt_path",
			"minecraft:short_grass", "minecraft:fern", "minecraft:cobblestone");

	public static void main(String[] args) throws IOException {
		Map<String, Object> root = readNbt(Path.of(args[0]));
		if (root.containsKey("Schematic")) {
			root = cast(root.get("Schematic"));
		}
		int width = ((Number) root.get("Width")).intValue() & 0xFFFF;
		int height = ((Number) root.get("Height")).intValue() & 0xFFFF;
		int length = ((Number) root.get("Length")).intValue() & 0xFFFF;
		Map<String, Object> palette = cast(root.get("Palette"));
		String[] names = new String[palette.size()];
		palette.forEach((name, index) -> names[((Number) index).intValue()] = name);
		byte[] data = (byte[]) root.get("BlockData");
		int[] cells = new int[width * height * length];
		for (int i = 0, n = 0; i < data.length; n++) {
			int value = 0;
			int shift = 0;
			while (true) {
				int b = data[i++] & 0xFF;
				value |= (b & 0x7F) << shift;
				shift += 7;
				if ((b & 0x80) == 0) {
					break;
				}
			}
			cells[n] = value;
		}
		int air = -1;
		for (int i = 0; i < names.length; i++) {
			if (names[i].equals("minecraft:air")) {
				air = i;
			}
		}
		int cx = width / 2;
		int cz = length / 2;
		// How full the octagon is at each height: the plateau is the highest layer it fills.
		int plateau = -1;
		int inside = 0;
		for (int z = 0; z < length; z++) {
			for (int x = 0; x < width; x++) {
				inside += inOctagon(x - cx, z - cz) ? 1 : 0;
			}
		}
		for (int y = 0; y < height; y++) {
			int solid = 0;
			for (int z = 0; z < length; z++) {
				for (int x = 0; x < width; x++) {
					if (inOctagon(x - cx, z - cz) && cells[(y * length + z) * width + x] != air) {
						solid++;
					}
				}
			}
			if (args.length > 1 && args[1].equals("--survey") && (y < 80 || y % 16 == 0)) {
				System.out.printf("y %3d  %6.2f%% of the octagon filled%n", y, 100.0 * solid / inside);
			}
			if (solid >= inside * 0.97) {
				plateau = y;
			}
		}
		System.out.println("octagon cells " + inside + ", plateau at y " + plateau + " of " + height + ", centre " + cx + "," + cz);
		if (args.length > 1 && args[1].equals("--survey")) {
			return;
		}

		// Only the palette entries actually used above the plateau, in a new order.
		Map<Integer, Integer> used = new LinkedHashMap<>();
		List<String> kept = new ArrayList<>();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(body);
		long blocks = 0;
		int top = 0;
		Map<String, Long> counts = new HashMap<>();
		for (int y = plateau + 1; y < height; y++) {
			List<int[]> rows = new ArrayList<>();
			for (int z = 0; z < length; z++) {
				List<int[]> runs = new ArrayList<>();
				int x = 0;
				while (x < width) {
					int state = cells[(y * length + z) * width + x];
					if (state == air || !keep(x - cx, z - cz, names[state])) {
						x++;
						continue;
					}
					int start = x;
					while (x < width && cells[(y * length + z) * width + x] == state && keep(x - cx, z - cz, names[state])) {
						x++;
					}
					int index = used.computeIfAbsent(state, s -> {
						kept.add(names[s]);
						return kept.size() - 1;
					});
					runs.add(new int[] {start - cx, x - start, index});
					blocks += x - start;
					counts.merge(names[state].replaceAll("\\[.*", ""), (long) (x - start), Long::sum);
					top = y - plateau - 1;
				}
				if (!runs.isEmpty()) {
					int[] row = new int[1 + runs.size() * 3];
					row[0] = z - cz;
					for (int r = 0; r < runs.size(); r++) {
						System.arraycopy(runs.get(r), 0, row, 1 + r * 3, 3);
					}
					rows.add(row);
				}
			}
			out.writeShort(rows.size());
			for (int[] row : rows) {
				out.writeShort(row[0]);
				out.writeShort((row.length - 1) / 3);
				for (int i = 1; i < row.length; i++) {
					out.writeShort(row[i]);
				}
			}
		}
		out.flush();
		try (DataOutputStream file = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[1]), 1 << 16))) {
			file.writeInt(0x56434353);
			file.writeInt(1);
			file.writeShort(kept.size());
			for (String name : kept) {
				file.writeUTF(name);
			}
			file.writeShort(APOTHEM + OUTWORKS);
			file.writeShort(height - plateau - 1);
			file.write(body.toByteArray());
		}
		System.out.println("kept " + blocks + " blocks in " + kept.size() + " states, " + (top + 1) + " layers above the plateau");
		counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(15)
				.forEach(e -> System.out.println("  " + e.getKey() + " " + e.getValue()));
	}

	/** Inside the octagon everything is the castle's; just beyond it only what is built, not the land. */
	private static boolean keep(int u, int v, String state) {
		if (inOctagon(u, v)) {
			return true;
		}
		double beyond = Math.max(Math.max(Math.abs(u), Math.abs(v)), (Math.abs(u) + Math.abs(v)) / Math.sqrt(2)) - APOTHEM;
		return beyond < OUTWORKS && !LAND.contains(state.replaceAll("\\[.*", ""));
	}

	private static boolean inOctagon(int u, int v) {
		return Math.abs(u) <= APOTHEM && Math.abs(v) <= APOTHEM && Math.abs(u) + Math.abs(v) <= DIAGONAL;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object o) {
		return (Map<String, Object>) o;
	}

	// --- a reader for the little of NBT a schematic needs ---------------------------------------

	private static Map<String, Object> readNbt(Path file) throws IOException {
		byte[] raw;
		try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
			raw = in.readAllBytes();
		}
		ByteBuffer b = ByteBuffer.wrap(raw);
		int type = b.get();
		String name = string(b);
		Map<String, Object> root = new HashMap<>();
		root.put(name, payload(b, type));
		Object only = root.get(name);
		return only instanceof Map<?, ?> ? cast(only).containsKey("Schematic") || !name.isEmpty() ? wrap(name, only) : cast(only) : root;
	}

	private static Map<String, Object> wrap(String name, Object value) {
		if (name.equals("Schematic")) {
			Map<String, Object> m = new HashMap<>();
			m.put(name, value);
			return m;
		}
		return cast(value);
	}

	private static String string(ByteBuffer b) {
		int n = b.getShort() & 0xFFFF;
		byte[] s = new byte[n];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}

	private static Object payload(ByteBuffer b, int type) {
		switch (type) {
			case 1: return b.get();
			case 2: return b.getShort();
			case 3: return b.getInt();
			case 4: return b.getLong();
			case 5: return b.getFloat();
			case 6: return b.getDouble();
			case 7: {
				byte[] a = new byte[b.getInt()];
				b.get(a);
				return a;
			}
			case 8: return string(b);
			case 9: {
				int element = b.get();
				int n = b.getInt();
				List<Object> list = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					list.add(payload(b, element));
				}
				return list;
			}
			case 10: {
				Map<String, Object> map = new HashMap<>();
				while (true) {
					int t = b.get();
					if (t == 0) {
						return map;
					}
					String key = string(b);
					map.put(key, payload(b, t));
				}
			}
			case 11: {
				int[] a = new int[b.getInt()];
				for (int i = 0; i < a.length; i++) {
					a[i] = b.getInt();
				}
				return a;
			}
			case 12: {
				long[] a = new long[b.getInt()];
				for (int i = 0; i < a.length; i++) {
					a[i] = b.getLong();
				}
				return a;
			}
			default: throw new IllegalStateException("NBT type " + type);
		}
	}
}
