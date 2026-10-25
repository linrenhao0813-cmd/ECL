package com.ecl.operation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Explicit ownership of an upgrade across asynchronous stages; borrowers cannot release the owner. */
public final class InstanceOperationSession implements AutoCloseable {
    private final UUID instanceId;
    private final Path gameDirectory;
    private final AutoCloseable release;
    private boolean closed;
    private int borrowers;
    private boolean mutating;

    InstanceOperationSession(UUID instanceId, Path gameDirectory, AutoCloseable release) {
        this.instanceId = instanceId;
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.release = release;
    }

    public UUID instanceId() { return instanceId; }
    public Path gameDirectory() { return gameDirectory; }

    public synchronized Borrow borrow(UUID id, Path directory) throws IOException {
        if (closed || !instanceId.equals(id)
                || !gameDirectory.equals(Objects.requireNonNull(directory).toAbsolutePath().normalize())) {
            throw new IOException("Instance operation session is closed or belongs to another instance");
        }
        borrowers++;
        return new Borrow();
    }

    public synchronized Borrow borrowMutation(UUID id, Path directory) throws IOException {
        if (mutating) throw new IOException("Another transaction is already using this session");
        Borrow borrow = borrow(id, directory);
        mutating = true;
        borrow.mutation = true;
        return borrow;
    }

    @Override
    public void close() throws IOException {
        boolean releaseNow;
        synchronized (this) {
            if (closed) return;
            closed = true;
            releaseNow = borrowers == 0;
        }
        if (releaseNow) releaseOwnership();
    }

    private void releaseOwnership() throws IOException {
        try {
            release.close();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot release instance operation session", failure);
        }
    }

    public final class Borrow implements AutoCloseable {
        private boolean returned;
        private boolean mutation;

        private Borrow() { }

        @Override
        public void close() throws IOException {
            boolean releaseNow;
            synchronized (InstanceOperationSession.this) {
                if (returned) return;
                returned = true;
                if (mutation) mutating = false;
                borrowers--;
                releaseNow = closed && borrowers == 0;
            }
            if (releaseNow) releaseOwnership();
        }
    }
}
