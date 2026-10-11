package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenLeftEvent;
import com.sunnatbek.villagerciv.api.ExpeditionHungryEvent;
import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpeditionUpkeepTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(77);
	private final Simulation simulation = headless.simulation();
	private final List<ExpeditionHungryEvent> hungry = new ArrayList<>();
	private final List<CitizenLeftEvent> left = new ArrayList<>();
	private long villagers;

	ExpeditionUpkeepTest() {
		simulation.events().subscribe(ExpeditionHungryEvent.class, hungry::add);
		simulation.events().subscribe(CitizenLeftEvent.class, left::add);
	}

	private Expedition expeditionOf(int members) {
		while (simulation.expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members) {
			simulation.recruit(PLAYER, new UUID(2, villagers++), "none", null, false, 0, 64, 0);
		}
		return simulation.expeditionOf(PLAYER).orElseThrow();
	}

	@Test
	void previewTellsTheOddsWithoutAsking() {
		Recruitment.Offer offer = simulation.previewRecruit(PLAYER, new UUID(5, 5), "farmer", true);
		assertEquals(Recruitment.acceptChance(false, true), offer.chance());
		assertEquals(0, offer.cooldownTicks());
		assertFalse(offer.full());
		assertTrue(simulation.expeditionOf(PLAYER).isEmpty());

		expeditionOf(Recruitment.MAX_EXPEDITION_SIZE);
		assertTrue(simulation.previewRecruit(PLAYER, new UUID(5, 6), "none", false).full());
	}

	@Test
	void everyRecruitBringsFoodAndTheGroupEatsOneMealEachPerDay() {
		Expedition expedition = expeditionOf(4);
		assertEquals(4 * Recruitment.STARTING_MEALS_PER_RECRUIT, expedition.food());

		headless.runTicks(2 * ExpeditionUpkeep.TICKS_PER_DAY + 200);
		assertEquals(4 * Recruitment.STARTING_MEALS_PER_RECRUIT - 8, expedition.food());
		assertFalse(expedition.isStarving());
		assertEquals(List.of(), hungry);
	}

	@Test
	void hungryGroupAsksOnceThenRemindsDailyAndLosesLoyalty() {
		Expedition expedition = expeditionOf(4);
		int loyaltyBefore = expedition.members().iterator().next().loyalty();

		headless.runDays(Recruitment.STARTING_MEALS_PER_RECRUIT + 1);
		assertTrue(expedition.isStarving());
		assertTrue(hungry.size() >= 1 && hungry.size() <= 2, "asked " + hungry.size() + " times");
		assertTrue(expedition.members().iterator().next().loyalty() < loyaltyBefore);

		simulation.feed(expedition.id(), 40);
		headless.runTicks(ExpeditionUpkeep.STEP_TICKS + 200);
		assertFalse(expedition.isStarving());
		// The debt was capped: feeding does not vanish into days of missed meals.
		assertTrue(expedition.food() >= 40 - 2 * expedition.size(), "food left: " + expedition.food());
	}

	@Test
	void neglectedMembersLeaveInsteadOfTheGroupBreaking() {
		Expedition expedition = expeditionOf(3);
		headless.runDays(30);

		assertTrue(simulation.expeditionOf(PLAYER).isEmpty(), "everyone has gone home");
		assertEquals(3, left.size());
		assertEquals(0, simulation.state().expeditions().size());
		assertEquals(0, expedition.size());
	}

	@Test
	void fedAndAccompaniedGroupStaysForever() {
		Expedition expedition = expeditionOf(6);
		simulation.feed(expedition.id(), 6 * 60);
		headless.runDays(50);

		assertEquals(6, expedition.size());
		assertTrue(expedition.members().stream().allMatch(c -> c.loyalty() >= 60));
	}

	@Test
	void waitingInCampWearsLoyaltyDownSlowly() {
		Expedition expedition = expeditionOf(2);
		simulation.feed(expedition.id(), 1000);
		expedition.setState(Expedition.State.CAMPED);
		int before = expedition.members().iterator().next().loyalty();

		headless.runTicks(5 * ExpeditionUpkeep.TICKS_PER_DAY + 200);
		assertEquals(before - 5, expedition.members().iterator().next().loyalty());
	}

	@Test
	void fedCitizensRecoverAndStarvingOnesDoNot() {
		Expedition expedition = expeditionOf(2);
		Citizen hurt = expedition.members().iterator().next();
		hurt.setHealth(5f);

		headless.runTicks(ExpeditionUpkeep.STEP_TICKS * 2 + 200);
		assertEquals(13f, hurt.health(), "two steps of rest with food");
		headless.runDays(1);
		assertEquals(Citizen.MAX_HEALTH, hurt.health(), "never above full health");

		// A citizen with a body heals in the world instead; the record is left to follow it.
		hurt.setHealth(5f);
		hurt.setEmbodied(true);
		headless.runTicks(ExpeditionUpkeep.STEP_TICKS + 200);
		assertEquals(5f, hurt.health());

		hurt.setEmbodied(false);
		expedition.setFood(0);
		headless.runDays(2);
		// Until the first missed meal there is still a little recovery; after it, none.
		assertTrue(hurt.health() < Citizen.MAX_HEALTH / 2, "no food, no recovery: " + hurt.health());
	}

	@Test
	void betrayalCostsTrust() {
		Expedition expedition = expeditionOf(2);
		int before = expedition.members().iterator().next().loyalty();
		simulation.betrayed(expedition.id());
		assertEquals(before - ExpeditionUpkeep.BETRAYAL_LOYALTY_LOSS, expedition.members().iterator().next().loyalty());
	}

	@Test
	void disbandingEndsTheExpeditionAndReturnsItsMembers() {
		Expedition expedition = expeditionOf(3);
		List<Citizen> members = simulation.disband(expedition.id());

		assertEquals(3, members.size());
		assertTrue(simulation.expeditionOf(PLAYER).isEmpty());
		assertEquals(List.of(), simulation.disband(expedition.id()));
	}

	@Test
	void foodStateSurvivesSaveAndLoad(@TempDir Path dir) throws IOException {
		Expedition expedition = expeditionOf(3);
		headless.runTicks(ExpeditionUpkeep.STEP_TICKS * 3 + 200);
		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());

		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(simulation.state()), store.export(loaded.state()));
		assertEquals(expedition.hunger(), loaded.state().expeditions().all().iterator().next().hunger());
	}
}
