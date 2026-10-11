package com.sunnatbek.villagerciv.api;

public record SettlementFoundedEvent(SettlementId settlement) implements SimEvent {
}
