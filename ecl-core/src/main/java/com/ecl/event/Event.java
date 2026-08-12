package com.ecl.event;

/**
 * Marker base type for all application events published on the {@link EventBus}.
 *
 * <p>Events are immutable value objects that describe something that has happened inside
 * the launcher (a download completed, a game exited, a setting changed). Subscribers receive
 * the same instance; an event must therefore never be mutated after publication.</p>
 */
public abstract class Event {
    /** Nanosecond timestamp captured when the event was created. */
    private final long timestampNanos = System.nanoTime();

    /** Name used for logging and debugging. Defaults to the simple class name. */
    public String name() {
        return getClass().getSimpleName();
    }

    /** Time (in nanoseconds) at which this event was instantiated. */
    public long timestampNanos() {
        return timestampNanos;
    }

    @Override
    public String toString() {
        return name();
    }
}