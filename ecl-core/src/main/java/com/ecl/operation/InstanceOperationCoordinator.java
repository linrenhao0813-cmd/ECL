package com.ecl.operation;

import com.ecl.modrinth.service.InstanceOperationLock;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.FileUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes mutating work for an instance; file transactions own their recovery journals. */
public final class InstanceOperationCoordinator implements InstanceOperationLock {
    private final Map<UUID, LockEntry> locks = new HashMap<>();

    /** A dedicated virtual owner preserves legacy thread-bound locking while callers borrow explicitly. */
    public InstanceOperationSession tryAcquireSession(UUID id, Path gameDirectory) throws IOException {
        Objects.requireNonNull(id, "instanceId");
        Path directory = gameDirectory.toAbsolutePath().normalize();
        FileUtil.validateExistingAncestors(directory.getRoot(), directory);
        CompletableFuture<InstanceOperationSession> ready = new CompletableFuture<>();
        CompletableFuture<Void> released = new CompletableFuture<>();
        CountDownLatch finish = new CountDownLatch(1);
        Thread.ofVirtual().name("ecl-instance-owner").start(() -> {
            try (AutoCloseable local = tryAcquire(id)) {
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
                    finish.await();
                }
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                released.completeExceptionally(failure);
            } finally {
                released.complete(null);
            }
        });
        try {
            return ready.join();
        } catch (CompletionException failure) {
            throw new IOException("Cannot acquire instance operation session", failure.getCause());
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
