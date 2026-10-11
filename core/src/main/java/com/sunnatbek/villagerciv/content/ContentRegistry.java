package com.sunnatbek.villagerciv.content;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable-per-reload table of data-driven definitions (blueprints, roles, techs, stages...).
 * A datapack reload swaps the whole table at once; saved records keep referring to entries by
 * id, and an id that disappeared simply resolves to empty instead of breaking the save.
 */
public final class ContentRegistry<T> {
	private final String name;
	private Map<String, T> entries = Map.of();

	public ContentRegistry(String name) {
		this.name = name;
	}

	public String name() {
		return name;
	}

	public void replaceAll(Map<String, T> definitions) {
		entries = Map.copyOf(definitions);
	}

	public Optional<T> get(String id) {
		return Optional.ofNullable(entries.get(id));
	}

	public Set<String> ids() {
		return entries.keySet();
	}

	public int size() {
		return entries.size();
	}
}
