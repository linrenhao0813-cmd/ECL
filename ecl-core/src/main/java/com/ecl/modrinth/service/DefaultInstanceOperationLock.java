package com.ecl.modrinth.service;

import com.ecl.operation.InstanceOperationCoordinator;

import java.util.UUID;

public final class DefaultInstanceOperationLock implements InstanceOperationLock {
    private final InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();

    @Override
    public AutoCloseable acquire(UUID instanceId) {
        return coordinator.acquire(instanceId);
    }

    @Override
    public boolean isLocked(UUID instanceId) {
        return coordinator.isLocked(instanceId);
    }
}
