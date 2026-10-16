package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Ledger;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the dark corners of a settlement, for its builders to light. A spot is dark if lamps and
 * torches give it less than a certain light, whatever the hour: what counts is how it will be at
 * night. Each lamp is a small thing made to be looked at, a post with a lantern on it, in one of
 * a few makes. Lamps are kept apart by rule, one to a square of ground (see {@link #scan}), so
 * a place is lit evenly and never crowded with them.
 *
 * <p>Lamps are kept off the streets, out of fields and groves, away from doors and the way into
 * the mine, and off the line of the wall: the same ground the levellers leave alone.
 */
public final class Lamplighter {
	/** Block light below which a spot counts as dim. A lantern gives 15 at its foot and loses one a block. */
	private static final int DIM_BELOW = 9;
	private static final int REACH = 44;
	/** The settlement is lit one lamp to a square of this many blocks, placed within this many of the square's corner. */
	private static final int CELL = 10;
	private static final int JITTER = 4;
	private static final int CELLS_PER_SCAN = 6;
	private static final int MAX_PENDING = 32;
	/** What a lamp takes out of the store. */
	public static final int WOOD_PER_LAMP = 2;
	public static final int ORE_PER_LAMP = 1;

	private static final class Ground {
		int cursor;
		final List<BlockPos> spots = new ArrayList<>();
	}

	private final Map<SettlementId, Ground> grounds = new HashMap<>();

	/**
	 * Looks over the next few squares of a settlement. The ground is taken in squares of
	 * {@link #CELL} blocks, and each square has exactly one place where a lamp may stand: somewhere
	 * in its near corner, a different spot from square to square so the lamps do not line up like
	 * a grid. That alone keeps lamps well apart, whatever the light says. A lamp found standing
	 * anywhere else in a square is one too many, and is taken down.
	 */
	void scan(ServerLevel level, Settlement settlement) {
		Ground ground = grounds.computeIfAbsent(settlement.id(), _ -> new Ground());
		int cells = (2 * REACH) / CELL + 1;
		for (int i = 0; i < CELLS_PER_SCAN; i++) {
			ground.cursor = (ground.cursor + 1) % (cells * cells);
			int cellX = ground.cursor % cells;
			int cellZ = ground.cursor / cells;
			int originX = settlement.anchorX() - REACH + cellX * CELL;
			int originZ = settlement.anchorZ() - REACH + cellZ * CELL;
			int place = Math.floorMod((cellX * 73856093) ^ (cellZ * 19349663), JITTER * JITTER);
			int lampX = originX + place % JITTER;
			int lampZ = originZ + place / JITTER;
			for (int dz = 0; dz < CELL; dz++) {
				for (int dx = 0; dx < CELL; dx++) {
					if (originX + dx != lampX || originZ + dz != lampZ) {
						takeDown(level, settlement, originX + dx, originZ + dz);
					}
				}
			}
			BlockPos foot = spotAt(level, settlement, lampX, lampZ);
			if (foot != null && !ground.spots.contains(foot) && ground.spots.size() < MAX_PENDING) {
				ground.spots.add(foot);
			}
		}
		settlement.setLampWork(!ground.spots.isEmpty() && canAfford(settlement));
	}

	/** Removes a lamp standing in this column, if there is one and the column is open ground of the settlement's. */
	private static void takeDown(ServerLevel level, Settlement settlement, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4) || !settlement.withinWalls(x, z) || Landscaper.keptOpen(settlement, x, z) || Landscaper.keptClear(settlement, x, z)) {
			return; // posts and lanterns in front of houses, on gates and in yards are part of those
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		// Down from the top of the column to the ground the lamp would stand on.
		for (int down = 0; down < 6 && !WorkSites.isGround(level.getBlockState(cursor)); down++) {
			cursor.move(0, -1, 0);
		}
		BlockPos foot = cursor.immutable();
		if (!isLampPart(level.getBlockState(foot.above()))) {
			return;
		}
		boolean lantern = false;
		for (int dy = 2; dy <= 4; dy++) {
			lantern |= level.getBlockState(foot.above(dy)).is(Blocks.LANTERN);
		}
		if (!lantern) {
			return; // a bare post or a bit of wall: not one of ours
		}
		for (int dy = 4; dy >= 1; dy--) {
			if (isLampPart(level.getBlockState(foot.above(dy)))) {
				level.setBlock(foot.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	private static boolean canAfford(Settlement settlement) {
		Ledger ledger = settlement.ledger();
		return ledger.available(Commodity.WOOD) >= WOOD_PER_LAMP && ledger.available(Commodity.ORE) >= ORE_PER_LAMP;
	}

	/** Hands out the dim spot nearest to a citizen: the ground block a lamp would stand on. Null if none is known. */
	public BlockPos take(ServerLevel level, Settlement settlement, BlockPos from) {
		Ground ground = grounds.get(settlement.id());
		if (ground == null || !canAfford(settlement)) {
			return null;
		}
		while (!ground.spots.isEmpty()) {
			int nearest = 0;
			for (int i = 1; i < ground.spots.size(); i++) {
				if (ground.spots.get(i).distSqr(from) < ground.spots.get(nearest).distSqr(from)) {
					nearest = i;
				}
			}
			BlockPos spot = ground.spots.remove(nearest);
			// Another lamp may have been lit near it since: look again.
			BlockPos now = spotAt(level, settlement, spot.getX(), spot.getZ());
			if (now != null) {
				return now;
			}
		}
		return null;
	}

	/** Puts a lamp up on this ground block, paid for from the store. */
	public void light(ServerLevel level, Settlement settlement, BlockPos foot) {
		Ledger ledger = settlement.ledger();
		if (spotAt(level, settlement, foot.getX(), foot.getZ()) == null || !canAfford(settlement)) {
			return;
		}
		ledger.take(Commodity.WOOD, WOOD_PER_LAMP);
		ledger.take(Commodity.ORE, ORE_PER_LAMP);
		BlockPos base = foot.above();
		// Three makes of lamp, each spot always getting the same one.
		switch (Math.floorMod(foot.getX() * 31 + foot.getZ() * 17, 3)) {
			case 0 -> {
				set(level, base, Blocks.COBBLESTONE_WALL.defaultBlockState());
				set(level, base.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, base.above(2), Blocks.LANTERN.defaultBlockState());
			}
			case 1 -> {
				set(level, base, Blocks.STONE_BRICK_WALL.defaultBlockState());
				set(level, base.above(), Blocks.STONE_BRICK_WALL.defaultBlockState());
				set(level, base.above(2), Blocks.LANTERN.defaultBlockState());
			}
			default -> {
				set(level, base, Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, base.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, base.above(2), Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, base.above(3), Blocks.LANTERN.defaultBlockState());
			}
		}
		Ground ground = grounds.get(settlement.id());
		if (ground != null && ground.spots.isEmpty()) {
			settlement.setLampWork(false);
		}
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState state) {
		level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
	}

	/** Whether a block is one a lamp is made of. A lamp in the way of a new building is taken down by this. */
	static boolean isLampPart(BlockState state) {
		return state.is(Blocks.COBBLESTONE_WALL) || state.is(Blocks.STONE_BRICK_WALL) || state.is(Blocks.SPRUCE_FENCE)
				|| (state.is(Blocks.LANTERN) && !state.getValue(BlockStateProperties.HANGING));
	}

	/** The ground block a lamp could stand on in this column, if the column is dim and free; else null. */
	private static BlockPos spotAt(ServerLevel level, Settlement settlement, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4) || !settlement.withinWalls(x, z) || Landscaper.keptOpen(settlement, x, z) || Landscaper.keptClear(settlement, x, z)) {
			return null;
		}
		int y = com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos foot = new BlockPos(x, y, z);
		BlockState ground = level.getBlockState(foot);
		if (!WorkSites.isGround(ground) || !ground.getFluidState().isEmpty()) {
			return null;
		}
		// Room for the tallest lamp, and open sky or leaves above: not under a roof or in a cave mouth.
		for (int dy = 1; dy <= 4; dy++) {
			BlockState above = level.getBlockState(foot.above(dy));
			if (!(above.isAir() || above.canBeReplaced()) || !above.getFluidState().isEmpty()) {
				return null;
			}
		}
		return level.getBrightness(LightLayer.BLOCK, foot.above()) < DIM_BELOW ? foot : null;
	}
}
