package com.sunnatbek.villagerciv.harness;

import com.sunnatbek.villagerciv.scheduler.QualityPreset;
import com.sunnatbek.villagerciv.sim.Simulation;
import com.sunnatbek.villagerciv.sim.SimulationState;

/**
 * Runs the simulation without Minecraft and without waiting for real time: a game day takes as
 * long as its 24000 ticks take to compute.
 */
public final class HeadlessSimulation {
	public static final long TICKS_PER_DAY = 24_000;

	private final FakeClock clock = new FakeClock();
	private final Simulation simulation;
	private long gameTick;

	public HeadlessSimulation(long seed) {
		this(SimulationState.fresh(seed), QualityPreset.LOW);
	}

	public HeadlessSimulation(SimulationState state, QualityPreset preset) {
		this.simulation = new Simulation(state, preset, clock::nanoTime);
	}

	public void runTicks(long ticks) {
		for (long i = 0; i < ticks; i++) {
			// The first half of every game day is daylight.
			simulation.setDaytime(gameTick % TICKS_PER_DAY < TICKS_PER_DAY / 2);
			simulation.tick(gameTick++, 10.0);
		}
	}

	public void runDays(int days) {
		runTicks(days * TICKS_PER_DAY);
	}

	public Simulation simulation() {
		return simulation;
	}

	public FakeClock clock() {
		return clock;
	}

	public long gameTick() {
		return gameTick;
	}
}
