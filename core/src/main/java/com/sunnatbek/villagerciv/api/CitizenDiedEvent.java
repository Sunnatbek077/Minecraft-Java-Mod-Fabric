package com.sunnatbek.villagerciv.api;

import java.util.UUID;

/**
 * A citizen died.
 *
 * @param player the player its group follows or was founded by
 */
public record CitizenDiedEvent(CitizenId citizen, UUID player) implements SimEvent {
}
