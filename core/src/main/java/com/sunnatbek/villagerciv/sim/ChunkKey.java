package com.sunnatbek.villagerciv.sim;

/** Chunk coordinates packed into one long, as territory is stored. */
public final class ChunkKey {
	private ChunkKey() {
	}

	public static long of(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	public static long ofBlock(int blockX, int blockZ) {
		return of(blockX >> 4, blockZ >> 4);
	}

	public static int x(long key) {
		return (int) (key >> 32);
	}

	public static int z(long key) {
		return (int) key;
	}
}
