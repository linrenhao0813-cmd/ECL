package com.ecl.modrinth.service;

import com.ecl.operation.InstanceOperationSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Serializes mutating work for one instance; implementations own their own wait policy. */
public interface InstanceOperationLock {
    AutoCloseable acquire(UUID instanceId);

    /**
     * Own the instance for a whole read-modify-publish sequence. Unlike {@link #acquire(UUID)} the
     * returned session also holds the inter-process lease, so a second launcher cannot change the
     * instance index between reading it and committing a replacement.
     */
    InstanceOperationSession acquireSession(UUID instanceId, Path gameDirectory) throws IOException;
}
