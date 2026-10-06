package com.ecl.operation;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceOperationCoordinatorTest {
    @Test
    void serializesOperationsForTheSameInstance() throws Exception {
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();
        UUID instanceId = UUID.randomUUID();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runUnchecked(() -> {
                try (AutoCloseable ignored = coordinator.acquire(instanceId)) {
                    firstEntered.countDown();
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                }
            }));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> runUnchecked(() -> {
                try (AutoCloseable ignored = coordinator.acquire(instanceId)) {
                    secondEntered.countDown();
                }
            }));

            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertFalse(coordinator.isLocked(instanceId));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptibleAcquireStopsWaitingWithoutLeakingUsers() throws Exception {
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();
        UUID instanceId = UUID.randomUUID();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        try (AutoCloseable first = coordinator.acquire(instanceId)) {
            Thread waiter = Thread.ofPlatform().start(() -> {
                waiting.countDown();
                try (AutoCloseable ignored = coordinator.acquireInterruptibly(instanceId)) {
                    throw new AssertionError("interrupted waiter acquired the lock");
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                } catch (Exception unexpected) {
                    throw new AssertionError(unexpected);
                }
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            waiter.interrupt();
            waiter.join(2_000);
            assertFalse(waiter.isAlive());
            assertTrue(interrupted.get());
        }

        assertFalse(coordinator.isLocked(instanceId));
        try (AutoCloseable ignored = coordinator.acquire(instanceId)) {
            assertTrue(coordinator.isLocked(instanceId));
        }
    }

    private static void runUnchecked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
