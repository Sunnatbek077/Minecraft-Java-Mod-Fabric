package com.sunnatbek.villagerciv.api;

public record CitizenRecruitedEvent(ExpeditionId expedition, CitizenId citizen) implements SimEvent {
}
