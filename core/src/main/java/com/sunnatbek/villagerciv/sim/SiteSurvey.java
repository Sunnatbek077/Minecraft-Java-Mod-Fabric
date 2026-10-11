package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;

import java.util.BitSet;

/**
 * Judges a place for a settlement from a {@link TerrainSnapshot}. Pure computation: safe to run
 * on a worker thread, and identical for identical snapshots.
 */
public final class SiteSurvey {
	/** Level, dry ground must fill this share of the core for a place to be settled at all. */
	private static final int CORE_RADIUS = 12;
	private static final double MIN_CORE_ROOM = 0.6;
	private static final int FLAT_RADIUS = 24;
	private static final int LEVEL_TOLERANCE = 2;
	private static final int CLIFF_HEIGHT = 4;

	public record Result(SiteScore score, Settlement.PlotMap plots) {
	}

	private SiteSurvey() {
	}

	public static Result analyse(TerrainSnapshot snapshot) {
		int radius = snapshot.radius();
		int size = snapshot.size();
		int centerIndex = snapshot.indexOf(0, 0);
		int centerHeight = snapshot.height(centerIndex);

		int columns = 0;
		int unknown = 0;
		int trees = 0;
		int stone = 0;
		int soil = 0;
		int flatArea = 0;
		int flatLevel = 0;
		int coreArea = 0;
		int coreRoom = 0;
		int rim = 0;
		int rimBarrier = 0;
		double nearestWaterSqr = Double.MAX_VALUE;

		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int distanceSqr = dx * dx + dz * dz;
				if (distanceSqr > radius * radius) {
					continue;
				}
				int index = snapshot.indexOf(dx, dz);
				Surface surface = snapshot.surface(index);
				columns++;
				if (surface == Surface.UNKNOWN) {
					unknown++;
					continue;
				}
				int rise = Math.abs(snapshot.height(index) - centerHeight);
				boolean level = surface.isLand() && rise <= LEVEL_TOLERANCE;
				switch (surface) {
					case TREE -> trees++;
					case STONE -> stone++;
					case SOIL -> soil++;
					case WATER -> nearestWaterSqr = Math.min(nearestWaterSqr, distanceSqr);
					default -> {
					}
				}
				if (distanceSqr <= FLAT_RADIUS * FLAT_RADIUS) {
					flatArea++;
					if (level) {
						flatLevel++;
					}
				}
				if (distanceSqr <= CORE_RADIUS * CORE_RADIUS) {
					coreArea++;
					if (level) {
						coreRoom++;
					}
				}
				if (distanceSqr >= (radius - 8) * (radius - 8)) {
					rim++;
					if (surface == Surface.WATER || surface == Surface.LAVA || rise >= CLIFF_HEIGHT) {
						rimBarrier++;
					}
				}
			}
		}

		int known = Math.max(1, columns - unknown);
		// Each aspect saturates at a "plenty" level instead of rewarding endless forest or rock.
		int water = nearestWaterSqr == Double.MAX_VALUE ? 0 : percent(1.0 - (Math.sqrt(nearestWaterSqr) - 12) / (radius - 12));
		int wood = percent(trees / (double) known / 0.12);
		int stoneScore = percent(stone / (double) known / 0.06);
		int flatness = percent(flatLevel / (double) Math.max(1, flatArea));
		int fertility = percent(soil / (double) known / 0.5);
		int defense = percent(rimBarrier / (double) Math.max(1, rim) / 0.5);
		int total = (water * 20 + wood * 20 + stoneScore * 15 + flatness * 25 + fertility * 15 + defense * 5) / 100;

		SiteScore.Problem problem = SiteScore.Problem.NONE;
		if (unknown > columns / 4) {
			problem = SiteScore.Problem.NOT_SURVEYED;
		} else if (!snapshot.surface(centerIndex).isLand()) {
			problem = SiteScore.Problem.NOT_ON_LAND;
		} else if (coreRoom < coreArea * MIN_CORE_ROOM) {
			problem = SiteScore.Problem.NO_ROOM;
		}
		SiteScore score = new SiteScore(total, water, wood, stoneScore, flatness, fertility, defense, problem);
		return new Result(score, new Settlement.PlotMap(snapshot.originX(), snapshot.originZ(), size, buildable(snapshot)));
	}

	/** A column is buildable if it is land and no neighbour is more than one block higher or lower. */
	private static BitSet buildable(TerrainSnapshot snapshot) {
		int size = snapshot.size();
		BitSet buildable = new BitSet(size * size);
		for (int z = 1; z < size - 1; z++) {
			for (int x = 1; x < size - 1; x++) {
				int index = z * size + x;
				if (!snapshot.surface(index).isLand()) {
					continue;
				}
				int height = snapshot.height(index);
				if (gentle(snapshot, index - 1, height) && gentle(snapshot, index + 1, height)
						&& gentle(snapshot, index - size, height) && gentle(snapshot, index + size, height)) {
					buildable.set(index);
				}
			}
		}
		return buildable;
	}

	private static boolean gentle(TerrainSnapshot snapshot, int neighbour, int height) {
		return snapshot.surface(neighbour).isLand() && Math.abs(snapshot.height(neighbour) - height) <= 1;
	}

	private static int percent(double fraction) {
		return (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * 100);
	}
}
