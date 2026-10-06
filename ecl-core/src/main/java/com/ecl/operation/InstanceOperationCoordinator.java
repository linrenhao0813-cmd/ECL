package com.ecl.operation;

import com.ecl.modrinth.service.InstanceOperationLock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes mutating work for an instance; file transactions own their recovery journals. */
public final class InstanceOperationCoordinator implements InstanceOperationLock {
    private final Map<UUID, LockEntry> locks = new HashMap<>();

    @Override
    public AutoCloseable acquire(UUID instanceId) {
        UUID id = Objects.requireNonNull(instanceId, "instanceId");
        LockEntry entry = retain(id);
        entry.lock.lock();
        return lease(id, entry);
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

    @Override
    public boolean isLocked(UUID instanceId) {
        synchronized (locks) {
            LockEntry entry = locks.get(Objects.requireNonNull(instanceId, "instanceId"));
            return entry != null && entry.lock.isLocked();
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
