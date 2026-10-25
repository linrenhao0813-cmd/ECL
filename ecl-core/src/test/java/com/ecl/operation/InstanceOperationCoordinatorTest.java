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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstanceOperationCoordinatorTest {
    @Test
    void sessionRetainsExclusiveOwnershipAcrossThreadsUntilLastBorrow(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();
        UUID id = UUID.randomUUID();
        InstanceOperationSession session = coordinator.tryAcquireSession(id, directory);
        assertNotNull(session);
        AutoCloseable borrow = session.borrow(id, directory);
        assertThrows(java.io.IOException.class, () -> session.borrow(UUID.randomUUID(), directory));
        assertThrows(java.io.IOException.class, () -> session.borrow(id, directory.resolve("other")));
        try (var mutation = session.borrowMutation(id, directory)) {
            assertThrows(java.io.IOException.class, () -> session.borrowMutation(id, directory));
        }
        assertNull(coordinator.tryAcquire(id), "even the acquiring caller cannot reenter a session");
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> runUnchecked(session::close)).get(2, TimeUnit.SECONDS);
        }
        assertNull(coordinator.tryAcquire(id), "an active borrower retains the lifecycle lock");
        assertThrows(java.io.IOException.class, () -> session.borrow(id, directory));
        borrow.close();
        assertCanAcquireImmediately(coordinator, id);
        try (var physical = com.ecl.util.InstanceOperationLease.tryAcquire(directory)) {
            assertNotNull(physical);
        }
    }

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
            assertCanAcquireImmediately(coordinator, instanceId);
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
            assertTrue(isHeldWhenProbedFromAnotherThread(coordinator, instanceId));
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

        assertCanAcquireImmediately(coordinator, instanceId);
    }

    @Test
    void tryAcquireFailsWithoutWaitingAndSucceedsAfterRelease() throws Exception {
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();
        UUID instanceId = UUID.randomUUID();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try (AutoCloseable ignored = coordinator.acquire(instanceId)) {
                Future<AutoCloseable> attempt = executor.submit(() -> coordinator.tryAcquire(instanceId));
                assertNull(attempt.get(1, TimeUnit.SECONDS));
            }
            try (AutoCloseable acquired = coordinator.tryAcquire(instanceId)) {
                assertNotNull(acquired);
            }
        }
        assertCanAcquireImmediately(coordinator, instanceId);
    }

    /** A successful blocking acquire on the current thread proves the lock is free. */
    private static void assertCanAcquireImmediately(InstanceOperationCoordinator coordinator,
                                                    UUID instanceId) throws Exception {
        try (AutoCloseable ignored = coordinator.acquire(instanceId)) {
            assertNotNull(ignored);
        }
    }

    /** Probes the lock from another thread, where a reentrant acquire cannot hide contention. */
    private static boolean isHeldWhenProbedFromAnotherThread(
            InstanceOperationCoordinator coordinator, UUID instanceId) throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AutoCloseable> attempt = executor.submit(() -> coordinator.tryAcquire(instanceId));
            AutoCloseable lease = attempt.get(2, TimeUnit.SECONDS);
            if (lease == null) {
                return true;
            }
            lease.close();
            return false;
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
