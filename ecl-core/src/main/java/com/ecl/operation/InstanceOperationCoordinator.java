package com.ecl.operation;

import com.ecl.modrinth.service.InstanceOperationLock;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.FileUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes mutating work for an instance; file transactions own their recovery journals. */
public final class InstanceOperationCoordinator implements InstanceOperationLock {
    private final Map<UUID, LockEntry> locks = new HashMap<>();

    /** Returns {@code null} instead of waiting when another operation owns the instance. */
    public InstanceOperationSession tryAcquireSession(UUID id, Path gameDirectory) throws IOException {
        return startSession(id, gameDirectory, false);
    }

    @Override
    public InstanceOperationSession acquireSession(UUID id, Path gameDirectory) throws IOException {
        return startSession(id, gameDirectory, true);
    }

    /**
     * A dedicated virtual owner preserves legacy thread-bound locking while callers borrow
     * explicitly. {@code wait} selects between queueing behind an in-process owner and reporting
     * immediately. Queueing honors interruption and has a 30-second deadline; the inter-process
     * lease is always tried once.
     */
    private InstanceOperationSession startSession(UUID id, Path gameDirectory, boolean wait)
            throws IOException {
        Objects.requireNonNull(id, "instanceId");
        Path directory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
        FileUtil.validateExistingAncestors(directory.getRoot(), directory);
        CompletableFuture<InstanceOperationSession> ready = new CompletableFuture<>();
        CompletableFuture<Void> released = new CompletableFuture<>();
        CountDownLatch finish = new CountDownLatch(1);
        Thread owner = Thread.ofVirtual().name("ecl-instance-owner").start(() -> {
            boolean handedOff = false;
            try (AutoCloseable local = wait ? acquireInterruptibly(id) : tryAcquire(id)) {
                if (local == null) {
                    ready.complete(null);
                    return;
                }
                try (InstanceOperationLease physical = InstanceOperationLease.tryAcquire(directory)) {
                    if (physical == null) {
                        ready.complete(null);
                        return;
                    }
                    ready.complete(new InstanceOperationSession(id, directory, () -> {
                        finish.countDown();
                        released.join();
                    }));
                    handedOff = true;
                    finish.await();
                }
            } catch (Throwable failure) {
                if (!handedOff) {
                    ready.completeExceptionally(failure);
                }
            } finally {
                released.complete(null);
            }
        });
        try {
            return ready.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            finish.countDown();
            owner.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for instance operation session", interrupted);
        } catch (TimeoutException timeout) {
            finish.countDown();
            owner.interrupt();
            throw new IOException("Instance is busy; timed out waiting for operation session", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Cannot acquire instance operation session", cause);
        }
    }

    @Override
    public AutoCloseable acquire(UUID instanceId) {
        UUID id = Objects.requireNonNull(instanceId, "instanceId");
        LockEntry entry = retain(id);
        entry.lock.lock();
        return lease(id, entry);
    }

    /** Returns {@code null} instead of waiting when another operation owns the instance. */
    public AutoCloseable tryAcquire(UUID instanceId) {
        UUID id = Objects.requireNonNull(instanceId, "instanceId");
        LockEntry entry = retain(id);
        if (entry.lock.tryLock()) {
            return lease(id, entry);
        }
        releaseUser(id, entry);
        return null;
    }

    /** Acquire the per-instance lock while honoring task cancellation/interruption. */
    public AutoCloseable acquireInterruptibly(UUID instanceId) throws InterruptedException {
        UUID id = Objects.requireNonNull(instanceId, "instanceId");
        LockEntry entry = retain(id);
        boolean acquired = false;
        try {
            entry.lock.lockInterruptibly();
            acquired = true;
            return lease(id, entry);
        } finally {
            if (!acquired) {
                releaseUser(id, entry);
            }
        }
    }

    private LockEntry retain(UUID instanceId) {
        synchronized (locks) {
            LockEntry entry = locks.computeIfAbsent(instanceId, ignored -> new LockEntry());
            entry.users++;
            return entry;
        }
    }

    private AutoCloseable lease(UUID instanceId, LockEntry entry) {
        return () -> {
            entry.lock.unlock();
            releaseUser(instanceId, entry);
        };
    }

    private void releaseUser(UUID instanceId, LockEntry entry) {
        synchronized (locks) {
            entry.users--;
            if (entry.users == 0) {
                locks.remove(instanceId, entry);
            }
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
