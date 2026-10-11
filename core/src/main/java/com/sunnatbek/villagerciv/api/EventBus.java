package com.sunnatbek.villagerciv.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Queued event bus. Publishing never runs a listener directly: events wait for the next
 * {@link #drain}, and events published while draining wait for the drain after that. One event
 * can therefore never cascade into an unbounded chain of reactions inside a single tick.
 */
public final class EventBus {
	private final Map<Class<?>, List<Consumer<SimEvent>>> listeners = new HashMap<>();
	private final ArrayDeque<SimEvent> queue = new ArrayDeque<>();

	@SuppressWarnings("unchecked")
	public <E extends SimEvent> void subscribe(Class<E> type, Consumer<? super E> listener) {
		listeners.computeIfAbsent(type, _ -> new ArrayList<>()).add((Consumer<SimEvent>) listener);
	}

	public void publish(SimEvent event) {
		queue.add(event);
	}

	/**
	 * Dispatches the events that were queued before this call, for as long as {@code hasBudget}
	 * allows. Whatever is left stays queued.
	 *
	 * @return number of events dispatched
	 */
	public int drain(BooleanSupplier hasBudget) {
		int pending = queue.size();
		int dispatched = 0;
		while (dispatched < pending && (dispatched == 0 || hasBudget.getAsBoolean())) {
			SimEvent event = queue.poll();
			List<Consumer<SimEvent>> targets = listeners.get(event.getClass());
			if (targets != null) {
				for (int i = 0; i < targets.size(); i++) {
					targets.get(i).accept(event);
				}
			}
			dispatched++;
		}
		return dispatched;
	}

	public int pending() {
		return queue.size();
	}
}
