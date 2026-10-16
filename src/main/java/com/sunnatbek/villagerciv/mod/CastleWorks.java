package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Fortification;
import com.sunnatbek.villagerciv.sim.Castle;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.Simulation;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Puts the castle of an eight-sided city into the world: the pillars that rise from the towers of
 * its great wall, the stone platform they carry, and on it the castle itself, to the design in
 * {@code villagerciv/castle/octagon_castle.dat} (see tools/CastleConvert.java).
 *
 * <p>How far the work has come is the simulation's to say ({@link Castle#built}); this only shows
 * it. Every block has its place in one order, bottom to top, and within a layer round the compass,
 * so the castle rises a course at a time with the brigade going round it. A loaded chunk is
 * brought up to date a few thousand blocks a tick; one the game has not loaded is done when it is.
 * What has been done is never written down: after a restart each chunk is gone over again and
 * every block found already in place is passed by.
 */
public final class CastleWorks {
	public static final int NO_CEILING = Integer.MIN_VALUE;
	private static final String TEMPLATE = "/villagerciv/castle/octagon_castle.dat";
	/** Clear height left between the tallest building and the underside of the platform. */
	static final int GAP_ABOVE_TALLEST = 18;
	private static final int PLATFORM_THICKNESS = 3;
	private static final int UNDERSIDE_LAMP_SPACING = 8;
	/** Hidden lamps under the platform, so that the fields still grow and no monster is born in its shade. */
	private static final int LIGHT_SPACING = 5;
	private static final int LIGHT_ABOVE_GROUND = 3;
	private static final int SECTORS = 1024;
	private static final int PLACED_PER_TICK = 1_500;
	private static final int LOOKED_AT_PER_TICK = 20_000;
	private static final int RECENT = 256;

	private static Template template;

	private final EmbodimentController embodiment;
	private final Simulation simulation;
	private final Map<SettlementId, Plan> plans = new HashMap<>();
	private final Set<UUID> refusedDrops = new HashSet<>();
	private final Set<SettlementId> announced = new HashSet<>();

	CastleWorks(EmbodimentController embodiment, Simulation simulation) {
		this.embodiment = embodiment;
		this.simulation = simulation;
	}

	// --- the design ------------------------------------------------------------------------------

	/** The castle as drawn: its blocks above the plateau, relative to the centre of its octagon. */
	private record Template(BlockState[] palette, int extent, int layers, int[] cells, short[] states) {
	}

	private static synchronized Template template() {
		if (template != null) {
			return template;
		}
		try (InputStream raw = CastleWorks.class.getResourceAsStream(TEMPLATE);
				DataInputStream in = new DataInputStream(new GZIPInputStream(raw, 1 << 16))) {
			if (in.readInt() != 0x56434353 || in.readInt() != 1) {
				throw new IOException("not a castle design");
			}
			BlockState[] palette = new BlockState[in.readShort()];
			for (int i = 0; i < palette.length; i++) {
				palette[i] = parse(in.readUTF());
			}
			int extent = in.readShort();
			int layers = in.readShort();
			int[] cells = new int[1 << 20];
			short[] states = new short[1 << 20];
			int n = 0;
			for (int y = 0; y < layers; y++) {
				int rows = in.readShort();
				for (int r = 0; r < rows; r++) {
					int v = in.readShort();
					int runs = in.readShort();
					for (int k = 0; k < runs; k++) {
						int u0 = in.readShort();
						int length = in.readShort();
						short state = in.readShort();
						for (int u = u0; u < u0 + length; u++) {
							if (n == cells.length) {
								cells = Arrays.copyOf(cells, n * 2);
								states = Arrays.copyOf(states, n * 2);
							}
							cells[n] = pack(u, v, y);
							states[n++] = state;
						}
					}
				}
			}
			template = new Template(palette, extent, layers, Arrays.copyOf(cells, n), Arrays.copyOf(states, n));
			VillagerCivMod.LOGGER.info("Castle design read: {} blocks in {} layers", n, layers);
			return template;
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("The castle design could not be read", e);
		}
	}

	private static int pack(int u, int v, int y) {
		return (u + 512) << 21 | (v + 512) << 11 | y;
	}

	private static BlockState parse(String state) {
		// The design was drawn for an older game, in which the iron chain was called a chain.
		if (state.equals("minecraft:chain") || state.startsWith("minecraft:chain[")) {
			state = "minecraft:iron_chain" + state.substring("minecraft:chain".length());
		}
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
		} catch (Exception e) {
			VillagerCivMod.LOGGER.warn("Castle block {} is unknown; stone is used instead", state);
			return Blocks.STONE.defaultBlockState();
		}
	}

	// --- beginning it ----------------------------------------------------------------------------

	/**
	 * The patron hands over the charter for a castle. It is begun at once if the city is ready.
	 *
	 * @param player who gave it, told how it went; may be null
	 * @return true if the castle was begun, and the charter is used up
	 */
	boolean propose(ServerLevel level, ServerPlayer player, Settlement settlement) {
		String leader = leaderOf(settlement);
		if (settlement.hasCastle()) {
			tell(player, "message.villagerciv.castle.already", leader);
			return false;
		}
		if (!Castle.mayBegin(settlement)) {
			tell(player, "message.villagerciv.castle.not_ready", leader);
			return false;
		}
		int platformY = tallest(settlement) + GAP_ABOVE_TALLEST;
		Template design = template();
		if (platformY + design.layers() >= level.getMaxY()) {
			tell(player, "message.villagerciv.castle.too_low", leader);
			return false;
		}
		Plan plan = new Plan(level, settlement, platformY, design);
		Castle.begin(settlement, platformY, plan.total);
		plans.put(settlement.id(), plan);
		VillagerCivMod.LOGGER.info("Castle begun over {}: platform at y {}, {} blocks", settlement.name(), platformY, plan.total);
		tell(player, "message.villagerciv.castle.begun", leader);
		return true;
	}

	/** The top of the tallest thing the settlement has built. */
	private int tallest(Settlement settlement) {
		int top = settlement.grade();
		for (Building building : settlement.buildings()) {
			if (building.y() == Building.Y_UNKNOWN || building.stage() == Building.Stage.CONDEMNED) {
				continue;
			}
			int height = simulation.blueprints().get(building.blueprint()).map(Blueprint::height).orElse(16);
			top = Math.max(top, building.y() + height - 1);
		}
		return top;
	}

	/** A charter thrown onto the Town Center is as good as one handed to it. */
	void watchForCharters(ServerLevel level, Settlement settlement) {
		BlockPos centre = new BlockPos(settlement.anchorX(), settlement.anchorY(), settlement.anchorZ());
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(centre).inflate(1.5, 2.5, 1.5),
				e -> e.isAlive() && e.getItem().is(ModContent.CASTLE_CHARTER) && !refusedDrops.contains(e.getUUID()))) {
			ServerPlayer player = item.getOwner() instanceof ServerPlayer owner ? owner : null;
			if (propose(level, player, settlement)) {
				item.getItem().shrink(1);
				if (item.getItem().isEmpty()) {
					item.discard();
				}
			} else {
				refusedDrops.add(item.getUUID());
			}
		}
	}

	private static void tell(ServerPlayer player, String key, String leader) {
		if (player != null) {
			player.sendSystemMessage(Component.translatable(key, leader));
		}
	}

	private static String leaderOf(Settlement settlement) {
		for (com.sunnatbek.villagerciv.sim.Citizen citizen : settlement.members()) {
			if (citizen.role() == com.sunnatbek.villagerciv.sim.Role.LEADER) {
				return citizen.name();
			}
		}
		return "The Leader";
	}

	// --- where it stands -------------------------------------------------------------------------

	/** The underside of the castle's platform over this column, or {@link #NO_CEILING} if there is none. */
	public int ceilingAt(int x, int z) {
		for (Settlement settlement : simulation.state().settlements().all()) {
			if (settlement.hasCastle() && onPlatform(settlement, template == null ? 182 : template.extent() + 1, x, z)) {
				return settlement.castleY() - PLATFORM_THICKNESS + 1;
			}
		}
		return NO_CEILING;
	}

	private static boolean onPlatform(Settlement settlement, int apothem, int x, int z) {
		int dx = Math.abs(x - settlement.anchorX());
		int dz = Math.abs(z - settlement.anchorZ());
		return dx <= apothem && dz <= apothem && dx + dz <= apothem * Math.sqrt(2) + 1;
	}

	/**
	 * Somewhere for one of the brigade to stand and work: on top of a block laid lately, where the
	 * game is running. Null if nothing has been laid within sight.
	 */
	public BlockPos workSpot(ServerLevel level, Settlement settlement, Random random) {
		Plan plan = plans.get(settlement.id());
		if (plan == null || plan.recent.isEmpty()) {
			return null;
		}
		BlockPos[] recent = plan.recent.toArray(new BlockPos[0]);
		for (int tries = 0; tries < 8; tries++) {
			BlockPos laid = recent[random.nextInt(recent.length)];
			BlockPos feet = laid.above();
			if (level.isPositionEntityTicking(feet) && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
					&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
					&& !level.getBlockState(laid).getCollisionShape(level, laid).isEmpty()) {
				return feet;
			}
		}
		return null;
	}

	/** For the reports: blocks of the castle known to be in the world, and how many it has. */
	long[] progress(Settlement settlement) {
		Plan plan = plans.get(settlement.id());
		if (plan == null) {
			return new long[] {0, 0, 0};
		}
		long applied = 0;
		for (int cursor : plan.cursors.values()) {
			applied += cursor;
		}
		return new long[] {applied, plan.total, plan.layer};
	}

	// --- building it -----------------------------------------------------------------------------

	/** Brings the loaded part of every castle up to the work done, a little at a time. */
	void apply(ServerLevel level) {
		for (Settlement settlement : simulation.state().settlements().all()) {
			if (!settlement.hasCastle()) {
				continue;
			}
			Plan plan = plans.get(settlement.id());
			if (plan == null || plan.platformY != settlement.castleY()) {
				plan = new Plan(level, settlement, settlement.castleY(), template());
				plans.put(settlement.id(), plan);
			}
			plan.apply(level, settlement);
			if (Castle.isFinished(settlement) && plan.complete() && announced.add(settlement.id())) {
				ServerPlayer patron = level.getServer().getPlayerList().getPlayer(settlement.founder());
				tell(patron, "message.villagerciv.castle.finished", leaderOf(settlement));
			}
		}
	}

	/** Every block of one castle, sorted into the chunks they stand in. */
	private final class Plan {
		final int platformY;
		final int baseY;
		final int anchorX;
		final int anchorZ;
		final BlockState[] states;
		final int air;
		final int light;
		final long[] chunkKeys;
		final int[][] chunks;
		final int[] layerCounts;
		final Long2IntOpenHashMap cursors = new Long2IntOpenHashMap();
		final ArrayDeque<BlockPos> recent = new ArrayDeque<>();
		final int total;
		int layer = -1;
		int[] layerSectors;
		int round;

		Plan(ServerLevel level, Settlement settlement, int platformY, Template design) {
			this.platformY = platformY;
			this.anchorX = settlement.anchorX();
			this.anchorZ = settlement.anchorZ();
			int grade = settlement.grade();
			this.baseY = grade + 1;
			List<BlockState> palette = new ArrayList<>(Arrays.asList(design.palette()));
			Map<BlockState, Integer> extra = new HashMap<>();
			java.util.function.Function<BlockState, Integer> index = state -> extra.computeIfAbsent(state, s -> {
				palette.add(s);
				return palette.size() - 1;
			});
			this.air = index.apply(Blocks.AIR.defaultBlockState());
			this.light = index.apply(parse("minecraft:light[level=15]"));

			// What is added to the design, by position: later entries take the place of earlier ones.
			Long2IntOpenHashMap added = new Long2IntOpenHashMap();
			LongOpenHashSet clearOfCastle = new LongOpenHashSet();
			int apothem = design.extent() + 1;
			int top = index.apply(Blocks.STONE.defaultBlockState());
			int underside = index.apply(Blocks.STONE_BRICKS.defaultBlockState());
			int lamp = index.apply(parse("minecraft:ochre_froglight[axis=y]"));
			for (int dx = -apothem; dx <= apothem; dx++) {
				for (int dz = -apothem; dz <= apothem; dz++) {
					int x = anchorX + dx;
					int z = anchorZ + dz;
					if (!onPlatform(settlement, apothem, x, z)) {
						continue;
					}
					if (Math.floorMod(dx, LIGHT_SPACING) == 0 && Math.floorMod(dz, LIGHT_SPACING) == 0) {
						added.put(BlockPos.asLong(x, grade + LIGHT_ABOVE_GROUND, z), light);
					}
					added.put(BlockPos.asLong(x, platformY - 2, z),
							Math.floorMod(dx, UNDERSIDE_LAMP_SPACING) == 0 && Math.floorMod(dz, UNDERSIDE_LAMP_SPACING) == 0 ? lamp : underside);
					added.put(BlockPos.asLong(x, platformY - 1, z), top);
					added.put(BlockPos.asLong(x, platformY, z), top);
				}
			}
			for (Building tower : settlement.buildings()) {
				if (tower.blueprint().equals(Fortification.GREAT_TOWER) && tower.stage() == Building.Stage.COMPLETE) {
					pillar(level, tower, added, clearOfCastle, index);
				}
			}

			// Counted and sorted: bottom to top, and round the compass within a layer.
			Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
			int[] cells = design.cells();
			int castleBase = platformY + 1;
			for (int i = 0; i < cells.length; i++) {
				int x = anchorX + (cells[i] >>> 21) - 512;
				int z = anchorZ + ((cells[i] >>> 11) & 0x3FF) - 512;
				int y = castleBase + (cells[i] & 0x7FF);
				if (!clearOfCastle.contains(BlockPos.asLong(x, y, z))) {
					counts.addTo(chunkKey(x, z), 1);
				}
			}
			for (long pos : added.keySet()) {
				counts.addTo(chunkKey(BlockPos.getX(pos), BlockPos.getZ(pos)), 1);
			}
			Long2ObjectOpenHashMap<long[]> sorting = new Long2ObjectOpenHashMap<>();
			Long2IntOpenHashMap filled = new Long2IntOpenHashMap();
			for (var entry : counts.long2IntEntrySet()) {
				sorting.put(entry.getLongKey(), new long[entry.getIntValue()]);
			}
			int layers = castleBase + design.layers() - baseY;
			this.layerCounts = new int[layers];
			int total = 0;
			for (int i = 0; i < cells.length; i++) {
				int x = anchorX + (cells[i] >>> 21) - 512;
				int z = anchorZ + ((cells[i] >>> 11) & 0x3FF) - 512;
				int y = castleBase + (cells[i] & 0x7FF);
				if (!clearOfCastle.contains(BlockPos.asLong(x, y, z))) {
					total += file(sorting, filled, x, y, z, design.states()[i]);
				}
			}
			for (var entry : added.long2IntEntrySet()) {
				long pos = entry.getLongKey();
				total += file(sorting, filled, BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), entry.getIntValue());
			}
			this.total = total;
			this.states = palette.toArray(new BlockState[0]);
			this.chunkKeys = sorting.keySet().toLongArray();
			Arrays.sort(chunkKeys);
			this.chunks = new int[chunkKeys.length][];
			for (int c = 0; c < chunkKeys.length; c++) {
				long[] keyed = sorting.get(chunkKeys[c]);
				Arrays.sort(keyed);
				int[] packed = new int[keyed.length];
				for (int i = 0; i < keyed.length; i++) {
					packed[i] = (int) keyed[i];
				}
				chunks[c] = packed;
			}
		}

		private int file(Long2ObjectOpenHashMap<long[]> sorting, Long2IntOpenHashMap filled, int x, int y, int z, int state) {
			int dy = y - baseY;
			if (dy < 0 || dy >= layerCounts.length) {
				return 0;
			}
			long key = chunkKey(x, z);
			int at = filled.addTo(key, 1);
			int packed = (x & 15) << 28 | (z & 15) << 24 | dy << 13 | state;
			sorting.get(key)[at] = ((long) dy << 42 | (long) sector(x, z) << 32) | (packed & 0xFFFFFFFFL);
			layerCounts[dy]++;
			return 1;
		}

		/**
		 * The pillar that carries the platform up from one tower: a shell of deepslate on the
		 * tower's crown, a solid core, and stairs winding round the core from the guards' floor to
		 * the platform, coming out on it through a gap of their own.
		 */
		private void pillar(ServerLevel level, Building tower, Long2IntOpenHashMap added, LongOpenHashSet clearOfCastle,
				java.util.function.Function<BlockState, Integer> index) {
			int x0 = tower.x();
			int z0 = tower.z();
			int deck = tower.y() + 11;
			int shell = index.apply(Blocks.DEEPSLATE_BRICKS.defaultBlockState());
			int corner = index.apply(Blocks.POLISHED_DEEPSLATE.defaultBlockState());
			int core = index.apply(Blocks.DEEPSLATE_TILES.defaultBlockState());
			int glow = index.apply(Blocks.SHROOMLIGHT.defaultBlockState());
			int[][] ring = {{1, 1}, {2, 1}, {3, 1}, {4, 1}, {5, 1}, {5, 2}, {5, 3}, {5, 4}, {5, 5}, {4, 5}, {3, 5}, {2, 5}, {1, 5}, {1, 4}, {1, 3}, {1, 2}};
			for (int y = deck + 1; y <= platformY - PLATFORM_THICKNESS; y++) {
				for (int a = 0; a < 7; a++) {
					for (int b = 0; b < 7; b++) {
						if (a == 0 || a == 6 || b == 0 || b == 6) {
							added.put(BlockPos.asLong(x0 + a, y, z0 + b), (a == 0 || a == 6) && (b == 0 || b == 6) ? corner : shell);
						}
					}
				}
			}
			for (int y = deck + 3; y <= platformY; y++) {
				for (int a = 2; a <= 4; a++) {
					for (int b = 2; b <= 4; b++) {
						boolean lit = (y - deck) % 5 == 0 && (a != 3 || b != 3);
						added.put(BlockPos.asLong(x0 + a, y, z0 + b), lit ? glow : core);
					}
				}
			}
			// The ladder up from the tower's floor comes out in one cell of the ring: the stairs
			// pass over it only well above a guard's head.
			int start = 0;
			for (int i = 0; i < ring.length; i++) {
				if (level.getBlockState(new BlockPos(x0 + ring[i][0], tower.y() + 10, z0 + ring[i][1])).is(Blocks.LADDER)) {
					start = Math.floorMod(i - 3, ring.length);
				}
			}
			for (int y = deck + 1; y <= platformY; y++) {
				for (int[] cell : ring) {
					added.put(BlockPos.asLong(x0 + cell[0], y, z0 + cell[1]), air);
				}
			}
			for (int k = 0; deck + k <= platformY; k++) {
				int[] cell = ring[(start + k) % ring.length];
				int[] next = ring[(start + k + 1) % ring.length];
				String facing = next[0] > cell[0] ? "east" : next[0] < cell[0] ? "west" : next[1] > cell[1] ? "south" : "north";
				int y = deck + k;
				added.put(BlockPos.asLong(x0 + cell[0], y, z0 + cell[1]),
						(int) index.apply(parse("minecraft:deepslate_brick_stairs[facing=" + facing + ",half=bottom]")));
				for (int up = 1; up <= 2; up++) {
					long above = BlockPos.asLong(x0 + cell[0], y + up, z0 + cell[1]);
					if (y + up > platformY) {
						clearOfCastle.add(above);
					} else if (y + up <= platformY && y + up > deck) {
						added.put(above, air);
					}
				}
			}
		}

		private int sector(int x, int z) {
			double angle = Math.atan2(z - anchorZ, x - anchorX) + Math.PI;
			return Math.min(SECTORS - 1, (int) (angle / (2 * Math.PI) * SECTORS));
		}

		boolean complete() {
			for (int c = 0; c < chunks.length; c++) {
				if (cursors.get(chunkKeys[c]) < chunks[c].length) {
					return false;
				}
			}
			return true;
		}

		void apply(ServerLevel level, Settlement settlement) {
			long due = Castle.built(settlement) * total / Math.max(1, settlement.castleBlocks());
			// Which layer the work has reached, and how far round it.
			int duelayer = layerCounts.length;
			long within = 0;
			long counted = 0;
			for (int dy = 0; dy < layerCounts.length; dy++) {
				if (counted + layerCounts[dy] > due) {
					duelayer = dy;
					within = due - counted;
					break;
				}
				counted += layerCounts[dy];
			}
			int dueSector = 0;
			if (duelayer < layerCounts.length) {
				if (duelayer != layer) {
					layer = duelayer;
					layerSectors = new int[SECTORS];
					for (int c = 0; c < chunks.length; c++) {
						int cx = (int) (chunkKeys[c] >> 32) << 4;
						int cz = (int) chunkKeys[c] << 4;
						for (int cell : chunks[c]) {
							if ((cell >>> 13 & 0x7FF) == layer) {
								layerSectors[sector(cx + (cell >>> 28), cz + (cell >>> 24 & 15))]++;
							}
						}
					}
				}
				long sum = 0;
				while (dueSector < SECTORS && sum + layerSectors[dueSector] <= within) {
					sum += layerSectors[dueSector++];
				}
			} else {
				layer = duelayer;
			}

			int placed = 0;
			int looked = 0;
			List<Player> players = new ArrayList<>(level.players());
			for (int i = 0; i < chunks.length && placed < PLACED_PER_TICK && looked < LOOKED_AT_PER_TICK; i++) {
				int c = (round + i) % chunks.length;
				int cx = (int) (chunkKeys[c] >> 32);
				int cz = (int) chunkKeys[c];
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				int[] cells = chunks[c];
				int cursor = cursors.get(chunkKeys[c]);
				BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
				while (cursor < cells.length && placed < PLACED_PER_TICK && looked < LOOKED_AT_PER_TICK) {
					int cell = cells[cursor];
					int dy = cell >>> 13 & 0x7FF;
					int x = (cx << 4) + (cell >>> 28);
					int z = (cz << 4) + (cell >>> 24 & 15);
					if (dy > duelayer || (dy == duelayer && sector(x, z) >= dueSector)) {
						break;
					}
					pos.set(x, baseY + dy, z);
					BlockState target = states[cell & 0x1FFF];
					BlockState now = level.getBlockState(pos);
					looked++;
					if (now != target && !(target.is(Blocks.LIGHT) && !now.isAir())) {
						if (!target.getCollisionShape(level, pos).isEmpty() && standsIn(players, pos)) {
							break; // the patron is in the way: this chunk waits for them to move
						}
						level.setBlock(pos, target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						placed++;
						if (!target.isAir() && !target.is(Blocks.LIGHT) && dy >= platformY - baseY) {
							recent.addLast(pos.immutable());
							if (recent.size() > RECENT) {
								recent.removeFirst();
							}
						}
					}
					cursor++;
				}
				cursors.put(chunkKeys[c], cursor);
			}
			round = chunks.length == 0 ? 0 : (round + 1) % chunks.length;
		}

		private boolean standsIn(List<Player> players, BlockPos pos) {
			AABB cell = new AABB(pos);
			for (Player player : players) {
				if (player.getBoundingBox().intersects(cell)) {
					return true;
				}
			}
			return false;
		}
	}

	private static long chunkKey(int x, int z) {
		return (long) (x >> 4) << 32 | (z >> 4) & 0xFFFFFFFFL;
	}
}
