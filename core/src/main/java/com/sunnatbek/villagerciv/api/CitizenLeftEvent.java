package com.sunnatbek.villagerciv.api;

import java.util.UUID;

/**
 * A citizen gave up on its group and went home.
 *
 * @param player     the player the group follows or was founded by
 * @param groupEnded true if this was the last member of an expedition, which is thereby over
 */
public record CitizenLeftEvent(UUID player, CitizenId citizen, String name, boolean groupEnded) implements SimEvent {
}
