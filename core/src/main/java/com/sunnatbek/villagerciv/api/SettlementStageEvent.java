package com.sunnatbek.villagerciv.api;

import java.util.UUID;

/**
 * A settlement changed stage.
 *
 * @param declined true if it fell back a stage rather than rising one
 */
public record SettlementStageEvent(SettlementId settlement, UUID player, boolean declined) implements SimEvent {
}
