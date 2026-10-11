package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.api.ExpeditionId;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A recruited group travelling with a player: a proto-civilization. It already uses the same
 * citizen records a settlement will, so founding later is a promotion, not a conversion.
 */
public final class Expedition implements SimRecord<ExpeditionId>, CitizenGroup {
	public enum State {
		/** Travelling with the player. */
		FOLLOWING,
		/** Waiting where the player left them (logout, death, another dimension). */
		CAMPED
	}

	private final ExpeditionId id;
	private final UUID player;
	private final long createdTick;
	private final Map<CitizenId, Citizen> members = new LinkedHashMap<>();
	private State state = State.FOLLOWING;
	private int food;
	private int hunger;
	private long lastUpkeepTick;
	private boolean starving;
	private boolean dirty;

	public Expedition(ExpeditionId id, UUID player, long createdTick) {
		this.id = id;
		this.player = player;
		this.createdTick = createdTick;
		this.lastUpkeepTick = createdTick;
	}

	@Override
	public ExpeditionId id() {
		return id;
	}

	/** The player this expedition travels with. */
	@Override
	public UUID player() {
		return player;
	}

	public long createdTick() {
		return createdTick;
	}

	public State state() {
		return state;
	}

	public void setState(State state) {
		if (this.state != state) {
			this.state = state;
			dirty = true;
		}
	}

	/** Shared food supply, in meals: one meal feeds one member for one game day. */
	public int food() {
		return food;
	}

	public void setFood(int food) {
		food = Math.max(0, food);
		if (this.food != food) {
			this.food = food;
			dirty = true;
		}
	}

	/** Tenths of a meal owed since the last one was eaten; grows with time and group size. */
	public int hunger() {
		return hunger;
	}

	public void setHunger(int hunger) {
		this.hunger = hunger;
		dirty = true;
	}

	public long lastUpkeepTick() {
		return lastUpkeepTick;
	}

	public void setLastUpkeepTick(long lastUpkeepTick) {
		this.lastUpkeepTick = lastUpkeepTick;
		dirty = true;
	}

	/** True while meals are being missed. */
	@Override
	public boolean isStarving() {
		return starving;
	}

	public void setStarving(boolean starving) {
		if (this.starving != starving) {
			this.starving = starving;
			dirty = true;
		}
	}

	@Override
	public Collection<Citizen> members() {
		return Collections.unmodifiableCollection(members.values());
	}

	@Override
	public Optional<Citizen> member(CitizenId id) {
		return Optional.ofNullable(members.get(id));
	}

	@Override
	public int size() {
		return members.size();
	}

	public void add(Citizen citizen) {
		citizen.attach(this);
		members.put(citizen.id(), citizen);
		dirty = true;
	}

	public Optional<Citizen> remove(CitizenId id) {
		Citizen citizen = members.remove(id);
		if (citizen != null) {
			dirty = true;
		}
		return Optional.ofNullable(citizen);
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
