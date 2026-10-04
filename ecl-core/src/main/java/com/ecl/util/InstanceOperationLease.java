package com.ecl.util;

import java.io.IOException;
import java.nio.file.Path;

/** Holds both the stable lifecycle lock and the legacy lock inside an instance directory. */
public final class InstanceOperationLease implements AutoCloseable {
    private FileLockLease lifecycleLock;
    private FileLockLease legacyLock;

    private InstanceOperationLease(FileLockLease lifecycleLock, FileLockLease legacyLock) {
        this.lifecycleLock = lifecycleLock;
        this.legacyLock = legacyLock;
    }

    public static InstanceOperationLease tryAcquire(Path instanceDirectory) throws IOException {
        Path normalized = instanceDirectory.toAbsolutePath().normalize();
        FileLockLease lifecycle = FileLockLease.tryAcquire(
                ManagedLockPaths.instanceOperation(normalized));
        if (lifecycle == null) return null;
        try {
            FileLockLease legacy = FileLockLease.tryAcquire(
                    normalized.resolve(".ecl").resolve("operation.lock"));
            if (legacy == null) {
                lifecycle.close();
                return null;
            }
            return new InstanceOperationLease(lifecycle, legacy);
        } catch (IOException | RuntimeException failure) {
            try {
                lifecycle.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Release the lock file located inside the instance while retaining the lifecycle lock. */
    public void releaseLegacyLock() throws IOException {
        if (legacyLock != null) {
            legacyLock.close();
            legacyLock = null;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            releaseLegacyLock();
        } catch (IOException error) {
            failure = error;
        }
        if (lifecycleLock != null) {
            try {
                lifecycleLock.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally {
                lifecycleLock = null;
            }
        }
        if (failure != null) throw failure;
    }
}
