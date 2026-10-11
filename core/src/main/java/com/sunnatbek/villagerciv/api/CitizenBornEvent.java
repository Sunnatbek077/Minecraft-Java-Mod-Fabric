package com.sunnatbek.villagerciv.api;

import java.util.UUID;

/** A settlement gained a citizen: a child born there, or a grown newcomer who walked in. */
public record CitizenBornEvent(SettlementId settlement, UUID player, CitizenId citizen, String name, boolean newcomer) implements SimEvent {
}
