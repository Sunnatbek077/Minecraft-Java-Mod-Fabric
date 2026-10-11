package com.sunnatbek.villagerciv.api;

import java.util.UUID;

/** Stable identity of a civilization. Records reference each other by id, never by object. */
public record CivilizationId(UUID value) {
	public static CivilizationId parse(String text) {
		return new CivilizationId(UUID.fromString(text));
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
