package com.ecl.modrinth.service;

import java.util.UUID;

/** Serializes mutating work for one instance; implementations own their own wait policy. */
public interface InstanceOperationLock {
    AutoCloseable acquire(UUID instanceId);
}
