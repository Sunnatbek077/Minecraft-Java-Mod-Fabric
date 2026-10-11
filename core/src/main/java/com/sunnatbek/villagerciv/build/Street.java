package com.sunnatbek.villagerciv.build;

/**
 * A straight street: a start cell, an axis-aligned direction and a length. Streets are three
 * cells wide (the centre line and one cell either side) and buildings stand along both sides,
 * facing them. They are paved bit by bit, as far as the buildings along them reach.
 */
public final class Street {
	public static final int HALF_WIDTH = 1;
	/** Lots begin this far from the centre line: the street edge, then a one-cell verge. */
	public static final int LOT_OFFSET = 3;

	private final int x;
	private final int z;
	private final int dx;
	private final int dz;
	private final int length;
	private int paved;

	public Street(int x, int z, int dx, int dz, int length, int paved) {
		this.x = x;
		this.z = z;
		this.dx = dx;
		this.dz = dz;
		this.length = length;
		this.paved = paved;
	}

	public int x() {
		return x;
	}

	public int z() {
		return z;
	}

	public int dx() {
		return dx;
	}

	public int dz() {
		return dz;
	}

	public int length() {
		return length;
	}

	/** How far along the street has been (or is being) paved. */
	public int paved() {
		return paved;
	}

	public void setPaved(int paved) {
		this.paved = paved;
	}

	/** World x of the cell {@code along} steps down the street and {@code across} cells to its left-hand side. */
	public int cellX(int along, int across) {
		return x + dx * along - dz * across;
	}

	public int cellZ(int along, int across) {
		return z + dz * along + dx * across;
	}

	public boolean covers(int blockX, int blockZ) {
		int rx = blockX - x;
		int rz = blockZ - z;
		int along = rx * dx + rz * dz;
		int across = -rx * dz + rz * dx;
		return along >= 0 && along < length && Math.abs(across) <= HALF_WIDTH;
	}
}
