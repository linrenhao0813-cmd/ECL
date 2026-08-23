package com.ecl.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small, dependency-free publish/subscribe bus used to decouple launcher components.
 *
 * <p>Registration is thread-safe. {@link #post(Event)} invokes every handler whose registered
 * type is a supertype (or the same type) of the posted event, on the posting thread. A single
 * failing handler is isolated and logged; it never prevents the remaining handlers from running.
 * Handlers are invoked in registration order.</p>
 *
 * <p>The bus is intentionally thin: it carries no event queue, no reflection, and no multi-thread
 * delivery guarantees. Subscribers that must update the user interface are responsible for
 * hopping onto the UI thread themselves (for example with {@code Platform.runLater}).</p>
 */
public final class EventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<? extends Event>, CopyOnWriteArrayList<EventHandler<? super Event>>>
            handlersByType = new ConcurrentHashMap<>();

    private volatile BiConsumer<Event, Throwable> errorSink =
            (event, error) -> LOGGER.warn("Event handler failed for {}", event.name(), error);

    /**
     * Register a handler for an event type. Handlers registered for a base type also receive
     * events of derived types.
     *
     * @return a handle that can be passed to {@link #unregister} to remove this subscription
     */
    public <E extends Event> Subscription register(Class<E> type, EventHandler<E> handler) {
        // The cast is safe: insertion is guarded by the Class<E> index of this bucket.
        @SuppressWarnings("unchecked")
        EventHandler<? super Event> widened = (EventHandler<? super Event>) handler;
        CopyOnWriteArrayList<EventHandler<? super Event>> bucket = handlersByType.compute(type,
                (ignored, existing) -> {
                    CopyOnWriteArrayList<EventHandler<? super Event>> selected = existing == null
                            ? new CopyOnWriteArrayList<>() : existing;
                    selected.add(widened);
                    return selected;
                });
        AtomicBoolean released = new AtomicBoolean();
        return new Subscription() {
            @Override
            public void release() {
                if (released.compareAndSet(false, true)) {
                    handlersByType.computeIfPresent(type, (ignored, existing) -> {
                        if (existing == bucket) {
                            existing.remove(widened);
                        }
                        return existing.isEmpty() ? null : existing;
                    });
                }
            }
        };
    }

    int subscriptionBucketCount() {
        return handlersByType.size();
    }

    /** Remove a previously registered subscription. No-op for an already-removed handle. */
    public void unregister(Subscription subscription) {
        if (subscription != null) {
            subscription.release();
        }
    }

    /**
     * Dispatch an event to all matching subscribers. Exceptions thrown by a handler are reported
     * to the configured error sink and do not abort dispatch to other handlers.
     */
    public void post(Event event) {
        if (event == null) {
            return;
        }
        handlersByType.forEach((type, handlers) -> {
            if (!type.isInstance(event)) {
                return;
            }
            for (EventHandler<? super Event> handler : handlers) {
                try {
                    @SuppressWarnings("unchecked")
                    EventHandler<Event> cast = (EventHandler<Event>) handler;
                    cast.handle(event);
                } catch (Throwable error) {
                    try {
                        errorSink.accept(event, error);
                    } catch (Throwable sinkFailure) {
                        LOGGER.warn("Event error sink failed for {}", event.name(), sinkFailure);
                        LOGGER.warn("Original event handler failure for {}", event.name(), error);
                    }
                }
            }
        });
    }

    /** Replace the default error handler. Never called with a null pair. */
    public void setErrorSink(BiConsumer<Event, Throwable> sink) {
        this.errorSink = sink == null
                ? (event, error) -> LOGGER.warn("Event handler failed for {}", event.name(), error)
                : sink;
    }

    /** Removable subscription returned by {@link #register}. */
    public interface Subscription {
        /** Remove this handler from the bus. Safe to call more than once. */
        void release();

        /** An already-detached subscription that releases nothing. */
        static Subscription noop() {
            return () -> { };
        }
    }
}
