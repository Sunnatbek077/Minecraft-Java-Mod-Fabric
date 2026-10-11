package com.sunnatbek.villagerciv.sim;

/**
 * An immutable copy of the surface around a point: one height and one surface kind per column.
 * It is taken on the game thread and then analysed anywhere, without ever touching the world again.
 */
public final class TerrainSnapshot {
	public enum Surface {
		/** Chunk not loaded when the snapshot was taken. */
		UNKNOWN,
		SOIL,
		SAND,
		STONE,
		TREE,
		WATER,
		LAVA,
		SNOW,
		/** Something built or otherwise unusual: not natural ground. */
		OTHER;

		boolean isLand() {
			return this == SOIL || this == SAND || this == STONE || this == SNOW;
		}
	}

	private static final Surface[] SURFACES = Surface.values();

	private final int centerX;
	private final int centerZ;
	private final int radius;
	private final int size;
	private final short[] heights;
	private final byte[] surfaces;

	public TerrainSnapshot(int centerX, int centerZ, int radius) {
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.radius = radius;
		this.size = 2 * radius + 1;
		this.heights = new short[size * size];
		this.surfaces = new byte[size * size];
	}

	public int centerX() {
		return centerX;
	}

	public int centerZ() {
		return centerZ;
	}

	public int radius() {
		return radius;
	}

	/** Columns per side. */
	public int size() {
		return size;
	}

	public int originX() {
		return centerX - radius;
	}

	public int originZ() {
		return centerZ - radius;
	}

	public int columnCount() {
		return size * size;
	}

	/** Fills column {@code index} (row-major from the origin corner). Only while the snapshot is being taken. */
	public void set(int index, int height, Surface surface) {
		heights[index] = (short) height;
		surfaces[index] = (byte) surface.ordinal();
	}

	public int blockX(int index) {
		return originX() + index % size;
	}

	public int blockZ(int index) {
		return originZ() + index / size;
	}

	public int height(int index) {
		return heights[index];
	}

	public Surface surface(int index) {
		return SURFACES[surfaces[index]];
	}

	public int indexOf(int dx, int dz) {
		return (dz + radius) * size + (dx + radius);
	}
}
