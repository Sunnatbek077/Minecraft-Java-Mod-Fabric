package com.sunnatbek.villagerciv.sim;

/** A top-level record that is saved in its own file. */
public interface SimRecord<I> {
	I id();

	/** True when the record changed since it was last written to disk. */
	boolean isDirty();

	void markDirty();

	void markClean();
}
