package com.sunnatbek.villagerciv.persistence;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Upgrades saved JSON one schema version at a time. Every file carries the version it was
 * written with; a step registered for version N turns an N document into an N+1 document.
 */
public final class SchemaMigrations {
	public static final String VERSION_KEY = "schema";

	private final int currentVersion;
	private final Map<Integer, UnaryOperator<JsonObject>> steps = new HashMap<>();

	public SchemaMigrations(int currentVersion) {
		this.currentVersion = currentVersion;
	}

	public int currentVersion() {
		return currentVersion;
	}

	public SchemaMigrations register(int fromVersion, UnaryOperator<JsonObject> step) {
		steps.put(fromVersion, step);
		return this;
	}

	/** @throws SaveFormatException if the document is newer than this mod or a step is missing */
	public JsonObject upgrade(JsonObject document) {
		if (!document.has(VERSION_KEY)) {
			throw new SaveFormatException("missing '" + VERSION_KEY + "' field");
		}
		int version = document.get(VERSION_KEY).getAsInt();
		if (version > currentVersion) {
			throw new SaveFormatException("written by a newer mod version (schema " + version + " > " + currentVersion + ")");
		}
		while (version < currentVersion) {
			UnaryOperator<JsonObject> step = steps.get(version);
			if (step == null) {
				throw new SaveFormatException("no migration from schema " + version);
			}
			document = step.apply(document);
			document.addProperty(VERSION_KEY, ++version);
		}
		return document;
	}
}
