package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Street;
import com.sunnatbek.villagerciv.build.WallPiece;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the holes in a settlement's ground, for its people to fill. A hole is a spot with
 * higher natural ground close by on at least three sides: a pit, a sinkhole, a cave mouth. A
 * slope has higher ground on one side and a gully on two, so hills and valleys are left as the
 * land made them. A hole is filled one layer at a time, and after each layer it is judged again,
 * so the filling stops by itself when the ground has closed up to its rim.
 *
 * <p>Some hollows are meant to be there and are never touched: the way down into a mine, fields
 * with their well, groves, streets, and everything on or beside a building's lot.
 */
public final class Landscaper {
	/** How far from the Town Center the ground is looked after. */
	private static final int REACH = 36;
	private static final int COLUMNS_PER_SCAN = 200;
	private static final int MAX_PENDING = 256;
	/** How near the higher ground has to be to count as a hole's rim, and how much higher. */
	private static final int RIM_DISTANCE = 6;
	private static final int RIM_RISE = 2;
	/** Deeper than this below its rim, a hole is a ravine: too much for shovels. */
	private static final int MAX_DEPTH = 14;
	/** The first steps of a mine stair lie open to the sky; that many, and a little around them, stay open. */
	private static final int OPEN_STAIR_STEPS = 8;
	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private static final class Ground {
		int cursor;
		final List<BlockPos> holes = new ArrayList<>();
	}

	private final Map<SettlementId, Ground> grounds = new HashMap<>();

	/** Looks over the next patch of a settlement's ground and notes the holes in it. */
	void scan(ServerLevel level, Settlement settlement) {
		Ground ground = grounds.computeIfAbsent(settlement.id(), _ -> new Ground());
		if (ground.holes.size() >= MAX_PENDING) {
			settlement.setGroundWork(true);
			return;
		}
		int side = 2 * REACH + 1;
		for (int i = 0; i < COLUMNS_PER_SCAN; i++) {
			int index = ground.cursor;
			ground.cursor = (ground.cursor + 1) % (side * side);
			int x = settlement.anchorX() - REACH + index % side;
			int z = settlement.anchorZ() - REACH + index / side;
			BlockPos top = holeAt(level, settlement, x, z);
			if (top != null && !ground.holes.contains(top)) {
				ground.holes.add(top);
			}
		}
		// The job board hires levellers on this word.
		settlement.setGroundWork(!ground.holes.isEmpty());
	}

	/**
	 * Hands out the hole nearest to a citizen, so that no two go for the same one.
	 *
	 * @return the ground block at the bottom of the hole, or null if none is known
	 */
	public BlockPos take(ServerLevel level, Settlement settlement, BlockPos from) {
		Ground ground = grounds.get(settlement.id());
		if (ground == null) {
			return null;
		}
		while (!ground.holes.isEmpty()) {
			int nearest = 0;
			for (int i = 1; i < ground.holes.size(); i++) {
				if (ground.holes.get(i).distSqr(from) < ground.holes.get(nearest).distSqr(from)) {
					nearest = i;
				}
			}
			BlockPos hole = ground.holes.remove(nearest);
			// The ground may have changed since it was noted: look again before sending anyone.
			BlockPos now = holeAt(level, settlement, hole.getX(), hole.getZ());
			if (now != null) {
				return now;
			}
		}
		return null;
	}

	/**
	 * Lays one layer of earth into a hole.
	 *
	 * @return false if it could not be done just now (somebody is standing in the way)
	 */
	public boolean fill(ServerLevel level, Settlement settlement, BlockPos bottom) {
		BlockPos now = holeAt(level, settlement, bottom.getX(), bottom.getZ());
		if (now == null) {
			return true; // no longer a hole: nothing to do
		}
		BlockPos above = now.above();
		if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(above)).isEmpty()) {
			return false;
		}
		if (WorkSites.isSoil(level.getBlockState(now))) {
			level.setBlockAndUpdate(now, Blocks.DIRT.defaultBlockState());
		}
		level.setBlockAndUpdate(above, Blocks.GRASS_BLOCK.defaultBlockState());
		// Still a hole a layer higher? Then it stays on the list.
		BlockPos next = holeAt(level, settlement, bottom.getX(), bottom.getZ());
		Ground ground = grounds.get(settlement.id());
		if (next != null && ground != null && ground.holes.size() < MAX_PENDING) {
			ground.holes.add(next);
		}
		return true;
	}

	/** The ground block at the bottom of the hole in this column, or null if the column is no hole. */
	private static BlockPos holeAt(ServerLevel level, Settlement settlement, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4) || keptOpen(settlement, x, z)) {
			return null;
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos top = new BlockPos(x, y, z);
		BlockState state = level.getBlockState(top);
		BlockState above = level.getBlockState(top.above());
		if (!WorkSites.isGround(state) || !state.getFluidState().isEmpty()
				|| !(above.isAir() || above.canBeReplaced()) || !above.getFluidState().isEmpty()) {
			return null;
		}
		int rims = 0;
		int lowestRim = Integer.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int[] side : SIDES) {
			for (int distance = 1; distance <= RIM_DISTANCE; distance++) {
				int rx = x + side[0] * distance;
				int rz = z + side[1] * distance;
				if (!level.hasChunk(rx >> 4, rz >> 4)) {
					break;
				}
				int ry = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, rz) - 1;
				// Only the land itself makes a rim: a house wall beside a yard does not make the yard a pit.
				if (ry >= y + RIM_RISE && WorkSites.isGround(level.getBlockState(cursor.set(rx, ry, rz)))) {
					rims++;
					lowestRim = Math.min(lowestRim, ry);
					break;
				}
			}
		}
		return rims >= 3 && lowestRim - y <= MAX_DEPTH ? top : null;
	}

	/** Ground that is meant to lie as it does. */
	static boolean keptOpen(Settlement settlement, int x, int z) {
		if (Math.abs(x - settlement.anchorX()) <= 3 && Math.abs(z - settlement.anchorZ()) <= 3) {
			return true;
		}
		for (Building building : settlement.buildings()) {
			if (x >= building.x() - 2 && x <= building.x() + building.sizeX() + 1
					&& z >= building.z() - 2 && z <= building.z() + building.sizeZ() + 1) {
				return true;
			}
		}
		for (WallPiece piece : settlement.wallPlan()) {
			if (x >= piece.x() - 1 && x <= piece.x() + piece.sizeX() && z >= piece.z() - 1 && z <= piece.z() + piece.sizeZ()) {
				return true;
			}
		}
		for (Street street : settlement.streets()) {
			if (street.covers(x, z)) {
				return true;
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
