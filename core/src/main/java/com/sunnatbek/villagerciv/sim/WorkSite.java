package com.sunnatbek.villagerciv.sim;

/**
 * A place where a job is done: a field, a grove or a mine entrance. Sites are squares on the
 * settlement's plot map; what stands on them is in the world, what is known about them is here.
 */
public final class WorkSite {
	/** Fields and groves are squares of this half-width (9×9). */
	public static final int HALF_WIDTH = 4;
	public static final int Y_UNKNOWN = Integer.MIN_VALUE;

	private final int id;
	private final JobType type;
	private final int x;
	private final int z;
	private final int directionX;
	private final int directionZ;
	private final Runnable onChange;
	private int y = Y_UNKNOWN;
	private int progress;
	private boolean exhausted;

	WorkSite(int id, JobType type, int x, int z, int directionX, int directionZ, Runnable onChange) {
		this.id = id;
		this.type = type;
		this.x = x;
		this.z = z;
		this.directionX = directionX;
		this.directionZ = directionZ;
		this.onChange = onChange;
	}

	public int id() {
		return id;
	}

	public JobType type() {
		return type;
	}

	/** Centre column of the site. */
	public int x() {
		return x;
	}

	public int z() {
		return z;
	}

	/** Unit vector pointing away from the Town Center; a mine is driven this way first. */
	public int directionX() {
		return directionX;
	}

	public int directionZ() {
		return directionZ;
	}

	/** Ground level of the site, or {@link #Y_UNKNOWN} until someone has stood there. */
	public int y() {
		return y;
	}

	public void setY(int y) {
		if (this.y != y) {
			this.y = y;
			onChange.run();
		}
	}

	/** Site-specific cursor; for a mine, how many steps of the stair have been cut. */
	public int progress() {
		return progress;
	}

	public void setProgress(int progress) {
		if (this.progress != progress) {
			this.progress = progress;
			onChange.run();
		}
	}

	/** True when nothing more can be won here (the mine hit water, or the bottom of the world). */
	public boolean isExhausted() {
		return exhausted;
	}

	public void setExhausted(boolean exhausted) {
		if (this.exhausted != exhausted) {
			this.exhausted = exhausted;
			onChange.run();
		}
	}
}
