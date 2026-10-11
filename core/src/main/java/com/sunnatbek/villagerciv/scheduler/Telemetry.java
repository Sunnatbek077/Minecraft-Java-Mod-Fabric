package com.sunnatbek.villagerciv.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Running cost counters for the scheduler. Nothing here allocates on the per-tick path. */
public final class Telemetry {
	private static final double EMA_ALPHA = 0.05;

	public record SystemStats(String system, long runs, long totalNanos, long maxNanos, long breakerTrips) {
		public double averageMicros() {
			return runs == 0 ? 0.0 : totalNanos / (double) runs / 1_000.0;
		}
	}

	private static final class Counter {
		long runs;
		long totalNanos;
		long maxNanos;
		long breakerTrips;
	}

	private final Map<String, Counter> systems = new LinkedHashMap<>();
	private long ticks;
	private long lastTickNanos;
	private long worstTickNanos;
	private double averageTickNanos;
	private int queuedTasks;
	private ThrottleLevel throttle = ThrottleLevel.NORMAL;

	void recordRun(String system, long nanos) {
		Counter counter = counter(system);
		counter.runs++;
		counter.totalNanos += nanos;
		counter.maxNanos = Math.max(counter.maxNanos, nanos);
	}

	void recordBreakerTrip(String system) {
		counter(system).breakerTrips++;
	}

	void endTick(long nanos, int queuedTasks, ThrottleLevel throttle) {
		ticks++;
		lastTickNanos = nanos;
		worstTickNanos = Math.max(worstTickNanos, nanos);
		averageTickNanos = ticks == 1 ? nanos : averageTickNanos + EMA_ALPHA * (nanos - averageTickNanos);
		this.queuedTasks = queuedTasks;
		this.throttle = throttle;
	}

	private Counter counter(String system) {
		Counter counter = systems.get(system);
		if (counter == null) {
			counter = new Counter();
			systems.put(system, counter);
		}
		return counter;
	}

	public long ticks() {
		return ticks;
	}

	public long lastTickNanos() {
		return lastTickNanos;
	}

	public long worstTickNanos() {
		return worstTickNanos;
	}

	/** Exponential moving average of the mod's cost per tick. */
	public double averageTickNanos() {
		return averageTickNanos;
	}

	public int queuedTasks() {
		return queuedTasks;
	}

	public ThrottleLevel throttle() {
		return throttle;
	}

	public List<SystemStats> systems() {
		List<SystemStats> result = new ArrayList<>(systems.size());
		systems.forEach((name, c) -> result.add(new SystemStats(name, c.runs, c.totalNanos, c.maxNanos, c.breakerTrips)));
		return result;
	}
}
