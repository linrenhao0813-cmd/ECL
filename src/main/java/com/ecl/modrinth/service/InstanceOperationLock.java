package com.ecl.modrinth.service;

import java.util.UUID;

public interface InstanceOperationLock {
    AutoCloseable acquire(UUID instanceId);

    boolean isLocked(UUID instanceId);
}
