package com.sunnatbek.villagerciv.scheduler;

import com.sunnatbek.villagerciv.harness.FakeClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationSchedulerTest {
	private final FakeClock clock = new FakeClock();
	// MEDIUM: 2.5 ms per tick
	private final SimulationScheduler scheduler = new SimulationScheduler(QualityPreset.MEDIUM, clock::nanoTime);
	private final List<String> log = new ArrayList<>();

	private SimTask costing(String name, double millis) {
		return _ -> {
			clock.advanceMillis(millis);
			log.add(name);
			return TaskResult.DONE;
		};
	}

	@Test
	void higherPriorityRunsFirst() {
		scheduler.submit("bg", TaskPriority.BACKGROUND, costing("background", 0.1));
		scheduler.submit("civ", TaskPriority.CIVILIZATION_AI, costing("civilization", 0.1));
		scheduler.submit("int", TaskPriority.INTERRUPT, costing("interrupt", 0.1));

		scheduler.tick(0, 10);
		assertEquals(List.of("interrupt", "civilization", "background"), log);
	}

	@Test
	void workBeyondTheBudgetWaitsForLaterTicks() {
		for (int i = 0; i < 10; i++) {
			scheduler.submit("work", TaskPriority.SETTLEMENT_AI, costing("t" + i, 1.0));
		}

		scheduler.tick(0, 10);
		// 1 ms each against 2.5 ms: the third task starts inside the budget, the fourth does not.
		assertEquals(3, log.size());
		assertEquals(7, scheduler.telemetry().queuedTasks());

		for (int tick = 1; tick < 4; tick++) {
			scheduler.tick(tick, 10);
		}
		assertEquals(10, log.size());
		assertTrue(scheduler.telemetry().worstTickNanos() <= 3_000_000, "tick cost stays within budget + one slice");
	}

	@Test
	void yieldingTasksShareTheQueueRoundRobin() {
		for (String name : List.of("a", "b")) {
			int[] slices = {3};
			scheduler.submit(name, TaskPriority.GROUP, _ -> {
				log.add(name);
				return --slices[0] > 0 ? TaskResult.CONTINUE : TaskResult.DONE;
			});
		}

		for (int tick = 0; tick < 3; tick++) {
			scheduler.tick(tick, 10);
		}
		assertEquals(List.of("a", "b", "a", "b", "a", "b"), log);
	}

	@Test
	void recurringTasksAreStaggeredByPhase() {
		scheduler.scheduleRecurring("s1", TaskPriority.SETTLEMENT_AI, 4, 0, costing("s1", 0));
		scheduler.scheduleRecurring("s2", TaskPriority.SETTLEMENT_AI, 4, 2, costing("s2", 0));

		for (int tick = 0; tick < 8; tick++) {
			scheduler.tick(tick, 10);
			log.add("|");
		}
		assertEquals("s1 | | s2 | | s1 | | s2 | |", String.join(" ", log));
	}

	@Test
	void slowServerShrinksBudgetAndThenDropsLowPriorities() {
		for (int i = 0; i < 4; i++) {
			scheduler.submit("ai", TaskPriority.SETTLEMENT_AI, costing("ai", 1.0));
		}
		scheduler.submit("int", TaskPriority.INTERRUPT, costing("interrupt", 0.1));

		scheduler.tick(0, 55); // critical: interrupts and embodied work only
		assertEquals(List.of("interrupt"), log);
		assertEquals(ThrottleLevel.CRITICAL, scheduler.telemetry().throttle());

		log.clear();
		scheduler.tick(1, 45); // reduced: half budget = 1.25 ms -> two 1 ms slices
		assertEquals(2, log.size());

		log.clear();
		scheduler.tick(2, 10);
		assertEquals(2, log.size());
	}

	@Test
	void systemThatKeepsOverrunningIsSuspendedThenRetried() {
		scheduler.scheduleRecurring("heavy", TaskPriority.BACKGROUND, 1, 0, costing("heavy", 6.0));
		scheduler.scheduleRecurring("light", TaskPriority.BACKGROUND, 1, 0, costing("light", 0.1));

		long tick = 0;
		for (; tick < 50; tick++) {
			scheduler.tick(tick, 10);
		}
		assertEquals(CircuitBreaker.STRIKES_TO_OPEN, log.stream().filter("heavy"::equals).count());
		assertTrue(log.stream().filter("light"::equals).count() >= 47, "other systems keep running");

		log.clear();
		for (; tick < 50 + CircuitBreaker.COOLDOWN_TICKS; tick++) {
			scheduler.tick(tick, 10);
		}
		assertTrue(log.contains("heavy"), "system is retried after the cooldown");
		long trips = scheduler.telemetry().systems().stream()
				.filter(s -> s.system().equals("heavy")).findFirst().orElseThrow().breakerTrips();
		assertTrue(trips >= 1);
	}

	@Test
	void failingTaskDoesNotStopTheTick() {
		scheduler.submit("broken", TaskPriority.INTERRUPT, _ -> {
			throw new IllegalStateException("boom");
		});
		scheduler.submit("ok", TaskPriority.BACKGROUND, costing("ok", 0.1));

		scheduler.tick(0, 10);
		assertEquals(List.of("ok"), log);
	}
}
