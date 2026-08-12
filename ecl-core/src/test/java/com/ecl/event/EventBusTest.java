package com.ecl.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {

    private static class BaseEvent extends Event {
    }

    private static class DerivedEvent extends BaseEvent {
    }

    @Test
    void dispatchesToHandlerRegisteredForExactType() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        bus.register(BaseEvent.class, event -> received.add(event.name()));

        bus.post(new BaseEvent());

        assertEquals(List.of(BaseEvent.class.getSimpleName()), received);
    }

    @Test
    void handlerRegisteredForSupertypeReceivesDerivedEvents() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        bus.register(BaseEvent.class, event -> received.add(event.name()));

        bus.post(new DerivedEvent());

        assertEquals(List.of(DerivedEvent.class.getSimpleName()), received);
    }

    @Test
    void handlerDoesNotReceiveUnrelatedEvents() {
        EventBus bus = new EventBus();
        AtomicInteger received = new AtomicInteger();
        bus.register(DerivedEvent.class, event -> received.incrementAndGet());

        bus.post(new BaseEvent());
        bus.post(new GameLifecycleEvent(GameLifecycleEvent.Phase.STARTED, "1.21", 0, null));

        assertEquals(0, received.get());
    }

    @Test
    void unsubscribeStopsFutureDispatchForThatHandlerOnly() {
        EventBus bus = new EventBus();
        EventBus.Subscription tracking = bus.register(BaseEvent.class, event -> { });
        List<String> received = new ArrayList<>();
        bus.register(BaseEvent.class, event -> received.add("second"));

        bus.post(new BaseEvent());
        bus.unregister(tracking);
        bus.post(new BaseEvent());

        assertEquals(List.of("second", "second"), received);
    }

    @Test
    void failingHandlerDoesNotBlockOtherHandlers() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        AtomicReference<Throwable> captured = new AtomicReference<>();
        bus.setErrorSink((event, error) -> captured.set(error));
        bus.register(BaseEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        bus.register(BaseEvent.class, event -> received.add("survived"));

        bus.post(new BaseEvent());

        assertEquals(List.of("survived"), received);
        assertSame(IllegalStateException.class, captured.get().getClass());
    }

    @Test
    void handlersRunInRegistrationOrder() {
        EventBus bus = new EventBus();
        List<Integer> order = new ArrayList<>();
        bus.register(BaseEvent.class, event -> order.add(1));
        bus.register(BaseEvent.class, event -> order.add(2));
        bus.register(BaseEvent.class, event -> order.add(3));

        bus.post(new BaseEvent());

        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void nullEventIsIgnoredSilently() {
        EventBus bus = new EventBus();
        AtomicInteger received = new AtomicInteger();
        bus.register(BaseEvent.class, event -> received.incrementAndGet());

        bus.post(null);

        assertEquals(0, received.get());
    }

    @Test
    void multipleUnregisterCallsAreSafe() {
        EventBus bus = new EventBus();
        AtomicInteger received = new AtomicInteger();
        EventBus.Subscription subscription = bus.register(BaseEvent.class, event -> received.incrementAndGet());

        subscription.release();
        subscription.release();
        bus.post(new BaseEvent());

        assertEquals(0, received.get());
        assertTrue(true, "releasing twice must not throw");
    }
}