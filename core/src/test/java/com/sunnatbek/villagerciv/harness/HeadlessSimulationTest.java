package com.sunnatbek.villagerciv.harness;

import com.sunnatbek.villagerciv.api.CivilizationCreatedEvent;
import com.sunnatbek.villagerciv.api.CivilizationId;
import com.sunnatbek.villagerciv.sim.Civilization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;

class HeadlessSimulationTest {
	@Test
	void hundredGameDaysRunInSeconds() {
		HeadlessSimulation headless = new HeadlessSimulation(42);
		assertTimeout(Duration.ofSeconds(10), () -> headless.runDays(100));
		assertEquals(100 * HeadlessSimulation.TICKS_PER_DAY, headless.simulation().scheduler().telemetry().ticks());
	}

	@Test
	void sameSeedGivesSameIds() {
		assertEquals(createThree(7), createThree(7));
	}

	@Test
	void creationEventArrivesOnNextTick() {
		HeadlessSimulation headless = new HeadlessSimulation(1);
		List<CivilizationId> seen = new ArrayList<>();
		headless.simulation().events().subscribe(CivilizationCreatedEvent.class, event -> seen.add(event.id()));

		Civilization civilization = headless.simulation().createCivilization("Riverfolk");
		assertEquals(List.of(), seen);
		headless.runTicks(1);
		assertEquals(List.of(civilization.id()), seen);
	}

	private static List<CivilizationId> createThree(long seed) {
		HeadlessSimulation headless = new HeadlessSimulation(seed);
		List<CivilizationId> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ids.add(headless.simulation().createCivilization("Civ " + i).id());
		}
		return ids;
	}
}
