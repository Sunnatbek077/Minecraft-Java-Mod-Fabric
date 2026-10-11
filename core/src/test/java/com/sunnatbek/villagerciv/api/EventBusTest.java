package com.sunnatbek.villagerciv.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventBusTest {
	private record Ping(int depth) implements SimEvent {
	}

	@Test
	void eventsPublishedWhileDrainingWaitForTheNextDrain() {
		EventBus bus = new EventBus();
		List<Integer> seen = new ArrayList<>();
		bus.subscribe(Ping.class, ping -> {
			seen.add(ping.depth());
			bus.publish(new Ping(ping.depth() + 1));
		});

		bus.publish(new Ping(0));
		assertEquals(1, bus.drain(() -> true));
		assertEquals(List.of(0), seen);
		assertEquals(1, bus.pending());

		bus.drain(() -> true);
		assertEquals(List.of(0, 1), seen);
	}

	@Test
	void drainStopsWhenBudgetIsSpentAndKeepsTheRest() {
		EventBus bus = new EventBus();
		List<Integer> seen = new ArrayList<>();
		bus.subscribe(Ping.class, ping -> seen.add(ping.depth()));
		for (int i = 0; i < 5; i++) {
			bus.publish(new Ping(i));
		}

		assertEquals(1, bus.drain(() -> false));
		assertEquals(4, bus.pending());
		assertEquals(4, bus.drain(() -> true));
		assertEquals(List.of(0, 1, 2, 3, 4), seen);
	}
}
