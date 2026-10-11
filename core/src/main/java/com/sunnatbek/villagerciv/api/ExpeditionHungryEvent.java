package com.sunnatbek.villagerciv.api;

/** The expedition has run out of food and asks its player for help. */
public record ExpeditionHungryEvent(ExpeditionId expedition) implements SimEvent {
}
