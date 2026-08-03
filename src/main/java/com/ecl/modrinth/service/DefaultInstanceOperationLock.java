package com.ecl.modrinth.service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class DefaultInstanceOperationLock implements InstanceOperationLock {
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable acquire(UUID instanceId) {
        UUID id = Objects.requireNonNull(instanceId, "instanceId");
        ReentrantLock lock = locks.computeIfAbsent(id, ignored -> new ReentrantLock());
        lock.lock();
        return () -> {
            try {
                lock.unlock();
            } finally {
                if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                    locks.remove(id, lock);
                }
            }
        };
    }

    @Override
    public boolean isLocked(UUID instanceId) {
        ReentrantLock lock = locks.get(instanceId);
        return lock != null && lock.isLocked();
    }
}
