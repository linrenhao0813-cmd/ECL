package com.ecl.event;

/**
 * Receives events of a specific type from the {@link EventBus}.
 *
 * <p>Handlers are invoked synchronously on the thread that posts the event unless the
 * posting thread chose a different dispatch strategy. Handlers must therefore complete
 * quickly and must never mutate the posted event.</p>
 *
 * @param <E> the event type this handler accepts
 */
@FunctionalInterface
public interface EventHandler<E extends Event> {

    void handle(E event);
}