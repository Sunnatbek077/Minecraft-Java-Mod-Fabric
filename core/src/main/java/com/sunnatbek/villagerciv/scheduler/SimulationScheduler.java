package com.sunnatbek.villagerciv.scheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Runs all simulation work inside a fixed time budget per server tick.
 *
 * <p>Work is queued by {@link TaskPriority}. Each tick the queues are served highest priority
 * first and round-robin within a queue; when the budget is spent the rest waits for the next
 * tick. The budget shrinks when the server itself is slow ({@link ThrottleLevel}), and a system
 * that keeps overrunning is suspended by its circuit breaker.
 */
public final class SimulationScheduler {
	private static final Logger LOGGER = System.getLogger("villagerciv/scheduler");
	private static final TaskPriority[] PRIORITIES = TaskPriority.values();

	private static final class Entry {
		final String system;
		final SimTask task;
		final Recurring owner;

		Entry(String system, SimTask task, Recurring owner) {
			this.system = system;
			this.task = task;
			this.owner = owner;
		}
	}

	private static final class Recurring {
		final TaskPriority priority;
		final long periodTicks;
		final Entry entry;
		long nextDueTick;
		boolean queued;

		Recurring(String system, TaskPriority priority, long periodTicks, long firstDueTick, SimTask task) {
			this.priority = priority;
			this.periodTicks = periodTicks;
			this.nextDueTick = firstDueTick;
			this.entry = new Entry(system, task, this);
		}
	}

	private final LongSupplier nanoTime;
	private final Telemetry telemetry = new Telemetry();
	private final TickContext context;
	private final EnumMap<TaskPriority, ArrayDeque<Entry>> queues = new EnumMap<>(TaskPriority.class);
	private final List<Recurring> recurring = new ArrayList<>();
	private final Map<String, CircuitBreaker> breakers = new HashMap<>();
	private QualityPreset preset;

	public SimulationScheduler(QualityPreset preset, LongSupplier nanoTime) {
		this.preset = preset;
		this.nanoTime = nanoTime;
		this.context = new TickContext(nanoTime);
		for (TaskPriority priority : PRIORITIES) {
			queues.put(priority, new ArrayDeque<>());
		}
	}

	/** Queues a one-shot task. */
	public void submit(String system, TaskPriority priority, SimTask task) {
		queues.get(priority).add(new Entry(system, task, null));
	}

	/**
	 * Registers a task that becomes due every {@code periodTicks}. {@code phaseTicks} shifts the
	 * cycle so that, for example, twenty settlements do not all replan on the same tick.
	 */
	public void scheduleRecurring(String system, TaskPriority priority, long periodTicks, long phaseTicks, SimTask task) {
		if (periodTicks < 1) {
			throw new IllegalArgumentException("periodTicks must be >= 1");
		}
		recurring.add(new Recurring(system, priority, periodTicks, Math.floorMod(phaseTicks, periodTicks), task));
	}

	public void tick(long gameTick, double serverMspt) {
		ThrottleLevel throttle = ThrottleLevel.forMspt(serverMspt);
		long fullBudget = preset.tickBudgetNanos();
		long start = nanoTime.getAsLong();
		context.reset(gameTick, start, (long) (fullBudget * throttle.budgetFactor()));
		enqueueDue(gameTick);

		// The first task always runs, so even a zero budget makes progress.
		boolean ranAny = false;
		outer:
		for (TaskPriority priority : PRIORITIES) {
			if (!throttle.allows(priority)) {
				break;
			}
			ArrayDeque<Entry> queue = queues.get(priority);
			for (int remaining = queue.size(); remaining > 0; remaining--) {
				if (ranAny && !context.hasBudget()) {
					break outer;
				}
				Entry entry = queue.poll();
				CircuitBreaker breaker = breakers.computeIfAbsent(entry.system, _ -> new CircuitBreaker());
				if (breaker.isOpen(gameTick)) {
					queue.add(entry);
					continue;
				}
				ranAny = true;
				if (run(entry, breaker, gameTick, fullBudget) == TaskResult.CONTINUE) {
					queue.add(entry);
				} else if (entry.owner != null) {
					entry.owner.queued = false;
				}
			}
		}
		telemetry.endTick(nanoTime.getAsLong() - start, queuedTasks(), throttle);
	}

	private TaskResult run(Entry entry, CircuitBreaker breaker, long gameTick, long overrunNanos) {
		long before = nanoTime.getAsLong();
		TaskResult result;
		boolean tripped;
		try {
			result = entry.task.run(context);
			long nanos = nanoTime.getAsLong() - before;
			telemetry.recordRun(entry.system, nanos);
			tripped = breaker.record(nanos > overrunNanos, gameTick);
		} catch (RuntimeException e) {
			LOGGER.log(Level.ERROR, "System '" + entry.system + "' failed; task dropped", e);
			telemetry.recordRun(entry.system, nanoTime.getAsLong() - before);
			tripped = breaker.trip(gameTick);
			result = TaskResult.DONE;
		}
		if (tripped) {
			telemetry.recordBreakerTrip(entry.system);
			LOGGER.log(Level.WARNING, "System ''{0}'' suspended for {1} ticks (circuit breaker)",
					entry.system, CircuitBreaker.COOLDOWN_TICKS);
		}
		return result;
	}

	private void enqueueDue(long gameTick) {
		for (int i = 0; i < recurring.size(); i++) {
			Recurring r = recurring.get(i);
			if (gameTick < r.nextDueTick) {
				continue;
			}
			// Skip periods that were missed entirely instead of replaying them.
			r.nextDueTick += ((gameTick - r.nextDueTick) / r.periodTicks + 1) * r.periodTicks;
			if (!r.queued) {
				r.queued = true;
				queues.get(r.priority).add(r.entry);
			}
		}
	}

	private int queuedTasks() {
		int total = 0;
		for (TaskPriority priority : PRIORITIES) {
			total += queues.get(priority).size();
		}
		return total;
	}

	public Telemetry telemetry() {
		return telemetry;
	}

	public QualityPreset preset() {
		return preset;
	}

	public void setPreset(QualityPreset preset) {
		this.preset = preset;
	}
}
