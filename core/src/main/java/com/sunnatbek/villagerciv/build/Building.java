package com.sunnatbek.villagerciv.build;

/**
 * A building in a settlement: one blueprint placed at one spot, and how far its construction has
 * come. Construction is a single ordered list of steps (pave, level, place block after block),
 * and two cursors run along it:
 *
 * <ul>
 * <li>{@link #progress} is how much work has been done. It advances whether or not anyone is
 * near, so a settlement keeps building while its chunks are unloaded.</li>
 * <li>{@link #applied} is how much of that is actually in the world. It only advances while
 * the chunks are loaded, and then catches up to {@code progress} within a budget.</li>
 * </ul>
 *
 * <p>The gap between the two is the deferred world-edit queue. It needs no storage of its own,
 * because every step can be derived again from the blueprint and the two numbers.
 */
public final class Building {
	public static final int Y_UNKNOWN = Integer.MIN_VALUE;

	public enum Stage {
		/** The lot is taken, so nothing else is put there, but no work has begun. */
		PLANNED,
		UNDER_CONSTRUCTION,
		COMPLETE,
		/** Something was broken; the building is being gone over again. */
		UNDER_REPAIR
	}

	private final int id;
	private String blueprint;
	private final Blueprint.Category category;
	private final int x;
	private final int z;
	private final int rotation;
	private final int sizeX;
	private final int sizeZ;
	private final int street;
	private final int paveFrom;
	private final int paveTo;
	private int totalSteps;
	private final int beds;
	private int costWood;
	private int costStone;
	private final Runnable onChange;
	private int y = Y_UNKNOWN;
	private int design;
	private Stage stage = Stage.UNDER_CONSTRUCTION;
	private int progress;
	private int applied;

	public Building(int id, String blueprint, Blueprint.Category category, int x, int z, int rotation, int sizeX, int sizeZ,
			int street, int paveFrom, int paveTo, int totalSteps, int beds, int costWood, int costStone, Runnable onChange) {
		this.id = id;
		this.blueprint = blueprint;
		this.category = category;
		this.x = x;
		this.z = z;
		this.rotation = rotation;
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		this.street = street;
		this.paveFrom = paveFrom;
		this.paveTo = paveTo;
		this.totalSteps = totalSteps;
		this.beds = beds;
		this.costWood = costWood;
		this.costStone = costStone;
		this.onChange = onChange;
	}

	public int id() {
		return id;
	}

	public String blueprint() {
		return blueprint;
	}

	public Blueprint.Category category() {
		return category;
	}

	/** North-west corner of the footprint. */
	public int x() {
		return x;
	}

	public int z() {
		return z;
	}

	/** Quarter turns clockwise from the blueprint's own orientation (front to the south). */
	public int rotation() {
		return rotation;
	}

	/** Footprint as it stands in the world, after rotation. */
	public int sizeX() {
		return sizeX;
	}

	public int sizeZ() {
		return sizeZ;
	}

	/** Index of the street this building faces, and the stretch of it paved as part of this job. */
	public int street() {
		return street;
	}

	public int paveFrom() {
		return paveFrom;
	}

	public int paveTo() {
		return paveTo;
	}

	public int totalSteps() {
		return totalSteps;
	}

	/**
	 * The design was changed after this was built: it now takes a different number of steps. A
	 * building that was finished stays finished as far as the work goes; what the new design adds
	 * is put in when the building is next gone over.
	 */
	public void redesigned(int totalSteps) {
		if (this.totalSteps != totalSteps) {
			boolean done = progress >= this.totalSteps;
			this.totalSteps = totalSteps;
			progress = done ? totalSteps : Math.min(progress, totalSteps);
			applied = Math.min(applied, totalSteps);
			onChange.run();
		}
	}

	/**
	 * The building is enlarged to a bigger design on the same lot. The ground work is not done
	 * again: the cursors go back to the first block, and whatever of the new design already
	 * stands is left as it is.
	 *
	 * @param firstBlockStep index of the first block-laying step, the same in both designs
	 */
	public void enlarge(String blueprint, int totalSteps, int firstBlockStep, int costWood, int costStone) {
		this.blueprint = blueprint;
		this.totalSteps = totalSteps;
		this.costWood = costWood;
		this.costStone = costStone;
		this.progress = Math.min(firstBlockStep, totalSteps);
		this.applied = this.progress;
		this.stage = Stage.UNDER_CONSTRUCTION;
		onChange.run();
	}

	public int beds() {
		return beds;
	}

	public int costWood() {
		return costWood;
	}

	public int costStone() {
		return costStone;
	}

	/** Ground level of the lot, or {@link #Y_UNKNOWN} until its chunk has been seen loaded. */
	public int y() {
		return y;
	}

	public void setY(int y) {
		if (this.y != y) {
			this.y = y;
			onChange.run();
		}
	}

	/** Fingerprint of the design this was last built or gone over from; 0 if that was never noted. */
	public int design() {
		return design;
	}

	public void setDesign(int design) {
		if (this.design != design) {
			this.design = design;
			onChange.run();
		}
	}

	public Stage stage() {
		return stage;
	}

	public void setStage(Stage stage) {
		if (this.stage != stage) {
			this.stage = stage;
			onChange.run();
		}
	}

	public int progress() {
		return progress;
	}

	public int applied() {
		return applied;
	}

	/** Work done without anyone watching: only the progress cursor moves. */
	public void advance(int steps) {
		setCursors(Math.min(totalSteps, progress + steps), applied);
	}

	/** One step was carried out in the world. */
	public void stepApplied() {
		int next = Math.min(totalSteps, applied + 1);
		setCursors(Math.max(progress, next), next);
	}

	public void setCursors(int progress, int applied) {
		if (this.progress != progress || this.applied != applied) {
			this.progress = progress;
			this.applied = applied;
			onChange.run();
		}
	}

	public boolean isWorkDone() {
		return progress >= totalSteps;
	}

	public boolean contains(int blockX, int blockZ) {
		return blockX >= x && blockX < x + sizeX && blockZ >= z && blockZ < z + sizeZ;
	}
}
