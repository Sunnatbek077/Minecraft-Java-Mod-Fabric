package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CivilizationId;

/** Civilization record: the source of truth for everything shared by its settlements. */
public final class Civilization implements SimRecord<CivilizationId> {
	private final CivilizationId id;
	private final long foundedTick;
	private String name;
	private CivilizationTier tier;
	private boolean dirty;

	public Civilization(CivilizationId id, String name, long foundedTick, CivilizationTier tier) {
		this.id = id;
		this.name = name;
		this.foundedTick = foundedTick;
		this.tier = tier;
	}

	@Override
	public CivilizationId id() {
		return id;
	}

	public long foundedTick() {
		return foundedTick;
	}

	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
		dirty = true;
	}

	public CivilizationTier tier() {
		return tier;
	}

	public void setTier(CivilizationTier tier) {
		this.tier = tier;
		dirty = true;
	}

	@Override
	public boolean isDirty() {
		return dirty;
	}

	@Override
	public void markDirty() {
		dirty = true;
	}

	@Override
	public void markClean() {
		dirty = false;
	}
}
