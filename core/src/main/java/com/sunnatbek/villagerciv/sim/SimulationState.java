package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CivilizationId;
import com.sunnatbek.villagerciv.api.ExpeditionId;
import com.sunnatbek.villagerciv.api.SettlementId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Everything the simulation persists. Entities in the world are only a projection of this. */
public final class SimulationState {
	private final IdGenerator ids;
	private boolean indexDirty;
	private final RecordTable<CivilizationId, Civilization> civilizations = new RecordTable<>(this::markIndexDirty);
	private final RecordTable<ExpeditionId, Expedition> expeditions = new RecordTable<>(this::markIndexDirty);

	private final RecordTable<SettlementId, Settlement> settlements = new RecordTable<>(this::markIndexDirty);
	// Spatial index over all territory; rebuilt from the settlements, never saved.
	private Map<Long, SettlementId> territoryOwners;

	public SimulationState(IdGenerator ids) {
		this.ids = ids;
	}

	public static SimulationState fresh(long seed) {
		SimulationState state = new SimulationState(new IdGenerator(seed, 0));
		state.indexDirty = true;
		return state;
	}

	/** Ids and random rolls; see {@link IdGenerator}. */
	public IdGenerator ids() {
		return ids;
	}

	public RecordTable<CivilizationId, Civilization> civilizations() {
		return civilizations;
	}

	public RecordTable<ExpeditionId, Expedition> expeditions() {
		return expeditions;
	}

	public RecordTable<SettlementId, Settlement> settlements() {
		return settlements;
	}

	/** The settlement whose territory contains this chunk ({@link ChunkKey}), if any. */
	public Optional<Settlement> territoryOwner(long chunkKey) {
		if (territoryOwners == null) {
			territoryOwners = new HashMap<>();
			for (Settlement settlement : settlements.all()) {
				for (long key : settlement.territory()) {
					territoryOwners.put(key, settlement.id());
				}
			}
		}
		SettlementId owner = territoryOwners.get(chunkKey);
		return owner == null ? Optional.empty() : settlements.get(owner);
	}

	/** Must be called after any settlement's territory changed. */
	public void territoryChanged() {
		territoryOwners = null;
	}

	public Civilization createCivilization(String name, long gameTick) {
		Civilization civilization = new Civilization(new CivilizationId(ids.next()), name, gameTick, CivilizationTier.TRIBE);
		civilizations.add(civilization);
		return civilization;
	}

	public boolean isIndexDirty() {
		return indexDirty || ids.isDirty();
	}

	public void markIndexDirty() {
		indexDirty = true;
	}

	public void markIndexClean() {
		indexDirty = false;
		ids.markClean();
	}
}
