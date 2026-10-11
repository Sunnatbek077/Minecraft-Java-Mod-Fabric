package com.sunnatbek.villagerciv.api;

import java.util.UUID;

public record SettlementId(UUID value) {
	public static SettlementId parse(String text) {
		return new SettlementId(UUID.fromString(text));
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
