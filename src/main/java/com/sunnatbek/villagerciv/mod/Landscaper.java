package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Street;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a settlement's ground level. A settlement stands on the flat: all of the land it builds
 * on is brought to one height, its {@linkplain Settlement#grade() grade}. A bank is cut down to
 * it, a hollow is filled up to it, and a hill that reaches into the settlement is dug away.
 *
 * <p>Round anything already standing at another height the ground keeps to that height for a
 * couple of steps and then slopes, a block for a block, to the grade, so nothing is left on a
 * pedestal or in a pit. Past the settlement's edge the land is let rise or fall again in the
 * same way, a block for a block, so the levelled ground ends in a slope and not in a cliff.
 *
 * <p>Some ground is meant to lie as it does and is never touched: what a building stands on,
 * the way down into a mine, fields with their well, groves, and water.
 */
public final class Landscaper {
	/** How far past the settlement's edge the land is sloped back to what it was. */
	private static final int VERGE = 12;
	/** How near a building the ground keeps to the building's own level, and how far out its slope is looked for. */
	private static final int APRON = 2;
	private static final int STRUCTURE_REACH = 12;
	private static final int COLUMNS_PER_SCAN = 1_200;
	private static final int HEAVY_AT_SIGHT = 1_500;
	private static final long SCATTER = 1_000_003L;
	private static final int ROUNDS_BEFORE_RETRY = 2;
	private static final int MAX_PENDING = 512;
	/** Deeper than this below where it should be, a hollow is a ravine: too much for shovels. */
	private static final int MAX_FILL = 128;
	/** How far to every side of the spot a citizen is sent to one turn of work reaches. */
	private static final int WORK_REACH = 3;
	/** How far down through trees, lamps and plants the ground itself is looked for. */
	private static final int LOOK_DOWN = 32;
	/** The first steps of a mine stair lie open to the sky; that many, and a little around them, stay open. */
	private static final int OPEN_STAIR_STEPS = 8;
	private static final int NONE = Integer.MIN_VALUE;

	private static final class Ground {
		int cursor;
		/** Columns found out of level so far in this look over the whole ground. */
		int seenUneven;
		final List<BlockPos> uneven = new ArrayList<>();
		final Set<Long> noted = new HashSet<>();
		/** Columns that were worked on and are still not level: something stands in the way. Left alone for a while. */
		final Set<Long> stuck = new HashSet<>();
		int rounds;
	}

	private final Map<SettlementId, Ground> grounds = new HashMap<>();

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/**
	 * Looks over some more of a settlement's ground and notes where it is not level.
	 *
	 * <p>The ground is not gone over row by row. A city's ground is far wider than the part of
	 * the world the game is running, and a list filled from one edge sends every leveller to that
	 * edge, a long walk from anywhere, while the hill beside them stands. Columns are taken in a
	 * scattered order instead, so the list is always a sample of the whole of it and everyone
	 * finds work near where they are. Ground that is loaded but too far off for the game to run
	 * anyone there is on the list too: it is levelled from afar (see LandscapeGoal).
	 */
	void scan(ServerLevel level, Settlement settlement) {
		Ground ground = grounds.computeIfAbsent(settlement.id(), _ -> new Ground());
		int[] bounds = settlement.groundBounds();
		int west = bounds[0] - VERGE;
		int north = bounds[1] - VERGE;
		int width = bounds[2] - bounds[0] + 1 + 2 * VERGE;
		int columns = width * (bounds[3] - bounds[1] + 1 + 2 * VERGE);
		for (int i = 0; i < COLUMNS_PER_SCAN; i++) {
			// A stride by a prime larger than the count visits every column once before any twice.
			int index = (int) ((long) ground.cursor * SCATTER % columns);
			int x = west + index % width;
			int z = north + index / width;
			BlockPos top = level.hasChunk(x >> 4, z >> 4) && !ground.stuck.contains(key(x, z)) ? unevenAt(level, settlement, x, z) : null;
			if (top != null) {
				ground.seenUneven++;
				if (ground.noted.add(key(x, z))) {
					if (ground.uneven.size() < MAX_PENDING) {
						ground.uneven.add(top);
					} else {
						// A full list gives up an old spot for the new one, so that it does not go
						// stale: what is left on it longest is what nobody was near.
						BlockPos old = ground.uneven.set(level.getRandom().nextInt(MAX_PENDING), top);
						ground.noted.remove(key(old.getX(), old.getZ()));
					}
				}
			}
			if (++ground.cursor >= columns) {
				// Once round: how much there was to do in all is how many hands it wants.
				settlement.setGroundBacklog(ground.seenUneven);
				ground.cursor = 0;
				ground.seenUneven = 0;
				// What would not be levelled is tried again after a while: whatever was in the way may be gone.
				if (++ground.rounds % ROUNDS_BEFORE_RETRY == 0) {
					ground.stuck.clear();
				}
			}
		}
		// Enough has turned up already to call it heavy without waiting for the round to end.
		if (ground.seenUneven >= HEAVY_AT_SIGHT) {
			settlement.setGroundBacklog(ground.seenUneven);
		}
		// The job board hires levellers on this word.
		settlement.setGroundWork(!ground.uneven.isEmpty());
	}

	/**
	 * Measures the loaded ground inside the settlement's bounds, for the reports.
	 *
	 * @return {columns looked at, columns off their level, lowest ground, highest ground}
	 */
	int[] survey(ServerLevel level, Settlement settlement) {
		int[] bounds = settlement.groundBounds();
		int[] result = {0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
		for (int x = bounds[0]; x <= bounds[2]; x++) {
			for (int z = bounds[1]; z <= bounds[3]; z++) {
				int surface = level.hasChunk(x >> 4, z >> 4) ? surface(level, settlement, x, z) : NONE;
				if (surface == NONE || keptOpen(settlement, x, z)) {
					continue;
				}
				result[0]++;
				result[2] = Math.min(result[2], surface);
				result[3] = Math.max(result[3], surface);
				if (goal(level, settlement, x, z, surface) != surface || awash(level, settlement, x, z)) {
					result[1]++;
				}
			}
		}
		return result;
	}

	/** Where the waiting ground lies, for the reports: its bounds, and how far the nearest and the middle spot are from a point. */
	String pendingSpread(Settlement settlement, BlockPos from) {
		Ground ground = grounds.get(settlement.id());
		if (ground == null || ground.uneven.isEmpty()) {
			return "";
		}
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		List<Integer> away = new ArrayList<>();
		for (BlockPos pos : ground.uneven) {
			box[0] = Math.min(box[0], pos.getX());
			box[1] = Math.min(box[1], pos.getZ());
			box[2] = Math.max(box[2], pos.getX());
			box[3] = Math.max(box[3], pos.getZ());
			away.add((int) Math.sqrt(pos.distSqr(from)));
		}
		away.sort(null);
		return "x " + box[0] + ".." + box[2] + " z " + box[1] + ".." + box[3] + " from player nearest " + away.getFirst() + " median " + away.get(away.size() / 2);
	}

	/** How much ground is known to be waiting, for the reports. */
	/** Why each column that is still off its level is so, for the reports: each reason and how many. */
	Map<String, Integer> unevenReasons(ServerLevel level, Settlement settlement) {
		Map<String, Integer> reasons = new java.util.TreeMap<>();
		Ground ground = grounds.get(settlement.id());
		int[] bounds = settlement.groundBounds();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = bounds[0] - VERGE; x <= bounds[2] + VERGE; x++) {
			for (int z = bounds[1] - VERGE; z <= bounds[3] + VERGE; z++) {
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				int surface = surface(level, settlement, x, z);
				if (surface == NONE) {
					continue;
				}
				int target = goal(level, settlement, x, z, surface);
				if (target == surface && !awash(level, settlement, x, z)) {
					continue;
				}
				String why;
				if (target == surface) {
					why = "water or lava lying on it";
				} else if (target < surface) {
					why = "cut";
					for (int y = com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING, x, z); y > target; y--) {
						BlockState state = level.getBlockState(cursor.set(x, y, z));
						if (!state.isAir() && !state.typeHolder().is(BlockTags.LOGS) && !WorkSites.isNatural(state) && !Lamplighter.isLampPart(state)
								&& !state.is(Blocks.DIRT_PATH)) {
							why = "cut stopped by " + state.getBlock().getName().getString();
							break;
						}
					}
				} else if (target - surface > MAX_FILL) {
					why = "too deep to fill";
				} else {
					why = "fill";
					for (int y = surface + 1; y <= target; y++) {
						BlockState state = level.getBlockState(cursor.set(x, y, z));
						if (!state.isAir() && !WorkSites.isNatural(state) && !Lamplighter.isLampPart(state)
								&& !state.typeHolder().is(BlockTags.LOGS) && !state.typeHolder().is(BlockTags.LEAVES)) {
							why = "fill stopped by " + state.getBlock().getName().getString();
							break;
						}
					}
					if (why.equals("fill") && !level.getEntitiesOfClass(LivingEntity.class, new AABB(x, surface + 1, z, x + 1, target + 2, z + 1), Landscaper::notToBeBuried).isEmpty()) {
						why = "fill stopped by a creature";
					}
				}
				if (ground != null && ground.stuck.contains(key(x, z))) {
					why += " (set aside)";
				}
				reasons.merge(why, 1, Integer::sum);
			}
		}
		return reasons;
	}

	int stuck(Settlement settlement) {
		Ground ground = grounds.get(settlement.id());
		return ground == null ? 0 : ground.stuck.size();
	}

	int pending(Settlement settlement) {
		Ground ground = grounds.get(settlement.id());
		return ground == null ? 0 : ground.uneven.size();
	}

	/**
	 * Hands out the uneven spot nearest to a citizen, so that no two go for the same one.
	 *
	 * @return the ground block on top of that column as it is now, or null if none is known
	 */
	public BlockPos take(ServerLevel level, Settlement settlement, BlockPos from) {
		Ground ground = grounds.get(settlement.id());
		if (ground == null) {
			return null;
		}
		while (!ground.uneven.isEmpty()) {
			int nearest = 0;
			for (int i = 1; i < ground.uneven.size(); i++) {
				if (ground.uneven.get(i).distSqr(from) < ground.uneven.get(nearest).distSqr(from)) {
					nearest = i;
				}
			}
			BlockPos spot = ground.uneven.remove(nearest);
			ground.noted.remove(key(spot.getX(), spot.getZ()));
			// The ground may have changed since it was noted: look again before sending anyone.
			BlockPos now = unevenAt(level, settlement, spot.getX(), spot.getZ());
			if (now != null) {
				return now;
			}
		}
		return null;
	}

	/** A citizen's work on a spot: the column itself and those around it are brought to their level. */
	public void grade(ServerLevel level, Settlement settlement, BlockPos spot) {
		Ground ground = grounds.get(settlement.id());
		int rock = 0;
		for (int dx = -WORK_REACH; dx <= WORK_REACH; dx++) {
			for (int dz = -WORK_REACH; dz <= WORK_REACH; dz++) {
				rock += gradeColumn(level, settlement, spot.getX() + dx, spot.getZ() + dz);
				// Worked on and still not level: it is not offered again and again to no purpose.
				if (ground != null && unevenAt(level, settlement, spot.getX() + dx, spot.getZ() + dz) != null) {
					ground.stuck.add(key(spot.getX() + dx, spot.getZ() + dz));
				}
				if (ground != null && ground.noted.remove(key(spot.getX() + dx, spot.getZ() + dz))) {
					int x = spot.getX() + dx;
					int z = spot.getZ() + dz;
					ground.uneven.removeIf(pos -> pos.getX() == x && pos.getZ() == z);
				}
			}
		}
		// A hill dug away is a quarry of sorts: some of its rock is good building stone.
		if (rock >= 2) {
			settlement.ledger().add(com.sunnatbek.villagerciv.sim.Commodity.STONE, rock / 2);
		}
	}

	/**
	 * Brings one column to the level it should have.
	 *
	 * @return how many blocks of rock were dug out of it
	 */
	private static int gradeColumn(ServerLevel level, Settlement settlement, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return 0;
		}
		int surface = surface(level, settlement, x, z);
		if (surface == NONE) {
			return 0;
		}
		int target = goal(level, settlement, x, z, surface);
		if (target == surface) {
			// Level ground with water or lava run out over it from a spring: that is taken off.
			if (awash(level, settlement, x, z)) {
				drainPool(level, settlement, new BlockPos(x, com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z));
			}
			return 0;
		}
		BlockState cover = (onStreet(settlement, x, z) ? Blocks.DIRT_PATH : Blocks.GRASS_BLOCK).defaultBlockState();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int rock = 0;
		if (target < surface) {
			int top = com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING, x, z);
			for (int y = top; y > target; y--) {
				BlockState state = level.getBlockState(cursor.set(x, y, z));
				if (state.isAir()) {
					continue;
				}
				if (state.typeHolder().is(BlockTags.LOGS)) {
					WorkSites.fellTree(level, cursor.immutable());
					continue;
				}
				// A street laid over a bank comes down with the bank, and is laid again at the bottom.
				if (!WorkSites.isNatural(state) && !Lamplighter.isLampPart(state) && !state.is(Blocks.DIRT_PATH)) {
					return rock; // something of somebody's stands here: leave the column be
				}
				if (state.typeHolder().is(BlockTags.BASE_STONE_OVERWORLD)) {
					rock++;
				}
				// Breaking water or lava leaves it where it was: it is taken out instead.
				if (!state.getFluidState().isEmpty()) {
					level.setBlockAndUpdate(cursor, Blocks.AIR.defaultBlockState());
					continue;
				}
				level.destroyBlock(cursor, false);
			}
			if (WorkSites.isNatural(level.getBlockState(cursor.set(x, target, z))) && !level.getBlockState(cursor).isAir()
					&& !level.getBlockState(cursor).canBeReplaced()) {
				level.setBlockAndUpdate(cursor, cover);
			}
			return rock;
		}
		if (target - surface > MAX_FILL
				|| !level.getEntitiesOfClass(LivingEntity.class, new AABB(x, surface + 1, z, x + 1, target + 2, z + 1), Landscaper::notToBeBuried).isEmpty()) {
			return 0;
		}
		for (int y = surface + 1; y <= target; y++) {
			BlockState state = level.getBlockState(cursor.set(x, y, z));
			// A tree standing in the hollow comes down first, and its leaves with it.
			if (state.typeHolder().is(BlockTags.LOGS)) {
				WorkSites.fellTree(level, cursor.immutable());
				state = level.getBlockState(cursor);
			}
			if (state.typeHolder().is(BlockTags.LEAVES)) {
				level.destroyBlock(cursor, false);
				continue;
			}
			// Flowers, a lump of ore, a drift of snow: whatever the land itself left there is buried.
			if (!state.isAir() && !WorkSites.isNatural(state) && !Lamplighter.isLampPart(state)) {
				return 0;
			}
		}
		if (WorkSites.isSoil(level.getBlockState(cursor.set(x, surface, z))) || level.getBlockState(cursor).is(Blocks.DIRT_PATH)) {
			level.setBlockAndUpdate(cursor, Blocks.DIRT.defaultBlockState());
		}
		for (int y = surface + 1; y <= target; y++) {
			level.setBlockAndUpdate(cursor.set(x, y, z), y == target ? cover : Blocks.DIRT.defaultBlockState());
		}
		return 0;
	}

	/** The ground block on top of this column if the column is not at its level, or null if it is, or is not the settlement's to level. */
	private static BlockPos unevenAt(ServerLevel level, Settlement settlement, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		int surface = surface(level, settlement, x, z);
		if (surface == NONE) {
			return null;
		}
		int target = goal(level, settlement, x, z, surface);
		return (target == surface && !awash(level, settlement, x, z)) || target - surface > MAX_FILL ? null : new BlockPos(x, surface, z);
	}

	/**
	 * Height of the ground block in a column, under whatever grows or stands lightly on it, or
	 * {@link #NONE} if the column is water or has something built on it.
	 */
	private static int surface(ServerLevel level, Settlement settlement, int x, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
		// A pond, a flooded pit or a pool of lava on a walled settlement's ground is a hollow like
		// any other, and is filled: its ground is its bed. That goes for all the ground it keeps
		// level, the strip outside the wall too: water is not left standing against the wall's
		// foot. Further out the land is not touched at all, so the river past the town stays.
		boolean pond = settlement.wallPieces() > 0;
		for (int down = 0; down < LOOK_DOWN; down++) {
			BlockState state = level.getBlockState(cursor);
			if (!state.getFluidState().isEmpty()) {
				if (!pond) {
					return NONE;
				}
				cursor.move(0, -1, 0);
				continue;
			}
			if (WorkSites.isGround(state) || WorkSites.isOre(state) || state.is(Blocks.DIRT_PATH)) {
				return cursor.getY();
			}
			if (!state.isAir() && !WorkSites.isNatural(state) && !state.typeHolder().is(BlockTags.LOGS) && !Lamplighter.isLampPart(state)) {
				return NONE;
			}
			cursor.move(0, -1, 0);
		}
		return NONE;
	}

	/** The height the ground in this column should have, given the height it has. */
	/**
	 * The height the ground in this column should have. As {@link #target}, but a column that
	 * stands under water or lava is not left as a pool on the slope outside the wall: its bed is
	 * brought all the way up, so no water lies against the wall's foot.
	 */
	private static int goal(ServerLevel level, Settlement settlement, int x, int z, int surface) {
		int target = target(settlement, x, z, surface);
		if (!managed(settlement, x, z)) {
			return target;
		}
		BlockPos top = new BlockPos(x, com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
		return level.getFluidState(top).isEmpty() ? target : Math.max(target, target(settlement, x, z, settlement.grade()));
	}

	/** People are not buried under the fill; the fish in a pond that is being filled are. */
	private static boolean notToBeBuried(LivingEntity entity) {
		return entity instanceof net.minecraft.world.entity.player.Player || entity instanceof com.sunnatbek.villagerciv.mod.entity.CitizenEntity
				|| entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager || entity instanceof net.minecraft.world.entity.animal.Animal && !entity.isInWater();
	}

	private static final int POOL_LIMIT = 8_192;

	/**
	 * Takes away a whole sheet of water or lava lying above the settlement's level, all of it at
	 * once. Bailed out a block at a time, standing water closes over the gap again from the
	 * blocks on either side, so nothing is gained; emptied in one go, with its springs, it is gone.
	 * Water on ground the settlement does not keep (a field's channel, the land beyond the wall)
	 * is not touched, and neither is anything at or below the level: that is filled with earth.
	 */
	private static void drainPool(ServerLevel level, Settlement settlement, BlockPos start) {
		int grade = settlement.grade();
		java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
		Set<BlockPos> pool = new HashSet<>();
		open.add(start);
		pool.add(start);
		while (!open.isEmpty() && pool.size() < POOL_LIMIT) {
			BlockPos pos = open.poll();
			for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (next.getY() > grade && !pool.contains(next) && level.hasChunk(next.getX() >> 4, next.getZ() >> 4)
						&& level.getBlockState(next).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
						&& managed(settlement, next.getX(), next.getZ())) {
					pool.add(next);
					open.add(next);
				}
			}
		}
		for (BlockPos pos : pool) {
			if (pos.getY() > grade && level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
				// Without telling the neighbours: they are all going too, and are not to flow in first.
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			}
		}
	}

	/** Whether water or lava stands or runs on top of this column of ground the settlement keeps level. */
	private static boolean awash(ServerLevel level, Settlement settlement, int x, int z) {
		BlockPos top = new BlockPos(x, com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
		return level.getBlockState(top).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
				&& settlement.wallPieces() > 0 && managed(settlement, x, z);
	}

	/** Whether this column is ground the settlement keeps level at all. */
	private static boolean managed(Settlement settlement, int x, int z) {
		int[] bounds = settlement.groundBounds();
		int beyond = Math.max(Math.max(bounds[0] - x, x - bounds[2]), Math.max(bounds[1] - z, z - bounds[3]));
		return beyond <= VERGE && !keptOpen(settlement, x, z);
	}

	private static int target(Settlement settlement, int x, int z, int surface) {
		int[] bounds = settlement.groundBounds();
		int beyond = Math.max(Math.max(bounds[0] - x, x - bounds[2]), Math.max(bounds[1] - z, z - bounds[3]));
		if (beyond > VERGE || keptOpen(settlement, x, z)) {
			return surface;
		}
		int grade = settlement.grade();
		int low = grade - Math.max(0, beyond);
		int high = grade + Math.max(0, beyond);
		// The Town Center stands on its own ground, like any building.
		int[] range = {low, high};
		slopeTo(range, settlement.anchorY() - 1,
				Math.max(Math.abs(x - settlement.anchorX()), Math.abs(z - settlement.anchorZ())) - 1);
		for (Building building : settlement.buildings()) {
			if (building.y() == Building.Y_UNKNOWN) {
				continue;
			}
			int away = Math.max(Math.max(building.x() - x, x - (building.x() + building.sizeX() - 1)),
					Math.max(building.z() - z, z - (building.z() + building.sizeZ() - 1)));
			slopeTo(range, building.y(), away);
		}
		return Math.clamp(surface, range[0], range[1]);
	}

	/** Narrows what the ground may be to what suits something standing {@code away} steps off at height {@code y}. */
	private static void slopeTo(int[] range, int y, int away) {
		if (away <= STRUCTURE_REACH) {
			int slack = Math.max(0, away - APRON);
			range[0] = Math.clamp(range[0], y - slack, y + slack);
			range[1] = Math.clamp(range[1], y - slack, y + slack);
		}
	}

	private static boolean onStreet(Settlement settlement, int x, int z) {
		for (Street street : settlement.streets()) {
			if (street.covers(x, z)) {
				return true;
			}
		}
		return false;
	}

	/** Ground where nothing should be put up: beside a building, on a wall's line, on a street, by the Town Center. */
	static boolean keptClear(Settlement settlement, int x, int z) {
		if (Math.abs(x - settlement.anchorX()) <= 3 && Math.abs(z - settlement.anchorZ()) <= 3) {
			return true;
		}
		for (Building building : settlement.buildings()) {
			if (x >= building.x() - 2 && x <= building.x() + building.sizeX() + 1
					&& z >= building.z() - 2 && z <= building.z() + building.sizeZ() + 1) {
				return true;
			}
		}
		for (com.sunnatbek.villagerciv.build.WallPiece piece : settlement.wallPlan()) {
			if (x >= piece.x() - 1 && x <= piece.x() + piece.sizeX() && z >= piece.z() - 1 && z <= piece.z() + piece.sizeZ()) {
				return true;
			}
		}
		return onStreet(settlement, x, z);
	}

	/** Ground that is meant to lie as it does. */
	static boolean keptOpen(Settlement settlement, int x, int z) {
		if (Math.abs(x - settlement.anchorX()) <= 1 && Math.abs(z - settlement.anchorZ()) <= 1) {
			return true;
		}
		for (Building building : settlement.buildings()) {
			// A lot nobody has started on yet is ground like any other, and is levelled the sooner.
			if (building.y() != Building.Y_UNKNOWN && building.contains(x, z)) {
				// Of a tile of shaped wall only the wall itself: the ground beside it in the same
				// square is levelled like the rest.
				if (!com.sunnatbek.villagerciv.build.ShapedWall.isTile(building.blueprint())
						|| com.sunnatbek.villagerciv.build.ShapedWall.isWallCell(building.blueprint(), x - building.x(), z - building.z())) {
					return true;
				}
			}
		}
		for (WorkSite site : settlement.sites()) {
			if (site.type() == JobType.MINE) {
				// The way into the mine: the open part of the stair, with room to walk beside it.
				if (site.y() != WorkSite.Y_UNKNOWN) {
					for (int step = 0; step <= OPEN_STAIR_STEPS; step++) {
						BlockPos floor = WorkSites.stairFloor(site, step);
						if (Math.abs(x - floor.getX()) <= 1 && Math.abs(z - floor.getZ()) <= 1) {
							return true;
						}
					}
				} else if (Math.abs(x - site.x()) <= OPEN_STAIR_STEPS && Math.abs(z - site.z()) <= OPEN_STAIR_STEPS) {
					return true;
				}
			} else if (Math.abs(x - site.x()) <= WorkSite.HALF_WIDTH + 1 && Math.abs(z - site.z()) <= WorkSite.HALF_WIDTH + 1) {
				return true;
			}
		}
		return false;
	}
}
