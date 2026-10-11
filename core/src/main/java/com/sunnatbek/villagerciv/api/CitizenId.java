package com.sunnatbek.villagerciv.api;

import java.util.UUID;

/** Stable identity of a citizen. It outlives any entity that happens to represent the citizen. */
public record CitizenId(UUID value) {
	public static CitizenId parse(String text) {
		return new CitizenId(UUID.fromString(text));
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
