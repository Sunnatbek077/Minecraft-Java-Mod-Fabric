package com.sunnatbek.villagerciv.sim;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** All records of one kind, with the bookkeeping the save system needs. */
public final class RecordTable<I, R extends SimRecord<I>> {
	private final Map<I, R> records = new LinkedHashMap<>();
	private final Set<I> removed = new LinkedHashSet<>();
	private final Set<I> quarantined = new LinkedHashSet<>();
	private final Runnable onMembershipChange;

	RecordTable(Runnable onMembershipChange) {
		this.onMembershipChange = onMembershipChange;
	}

	/** Adds a newly created record. */
	public void add(R record) {
		record.markDirty();
		records.put(record.id(), record);
		onMembershipChange.run();
	}

	public boolean remove(I id) {
		if (records.remove(id) == null) {
			return false;
		}
		removed.add(id);
		onMembershipChange.run();
		return true;
	}

	public Optional<R> get(I id) {
		return Optional.ofNullable(records.get(id));
	}

	public Collection<R> all() {
		return Collections.unmodifiableCollection(records.values());
	}

	public int size() {
		return records.size();
	}

	// --- persistence hooks ---

	/** Adds a record read from disk, without marking anything dirty. */
	public void adopt(R record) {
		records.put(record.id(), record);
	}

	/**
	 * Remembers a record whose file could not be read. It stays listed in the index and its file
	 * is left untouched, so it can be repaired or restored from a backup later.
	 */
	public void quarantine(I id) {
		quarantined.add(id);
	}

	public Set<I> quarantined() {
		return Collections.unmodifiableSet(quarantined);
	}

	/** Records removed since the last save; their files still have to be deleted. */
	public Set<I> removed() {
		return removed;
	}
}
