package com.sunnatbek.villagerciv.api;

import java.util.UUID;

public record ExpeditionId(UUID value) {
	public static ExpeditionId parse(String text) {
		return new ExpeditionId(UUID.fromString(text));
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
