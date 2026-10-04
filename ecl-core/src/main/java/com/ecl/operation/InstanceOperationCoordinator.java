package com.ecl.operation;

import com.ecl.modrinth.service.InstanceOperationLock;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes all mutating work for an instance and journals semantic operations. */
public final class InstanceOperationCoordinator implements InstanceOperationLock {
    public static final String OPERATIONS_RELATIVE_PATH = ".ecl/operations";

    private final Map<UUID, LockEntry> locks = new HashMap<>();
    private final Clock clock;

    public InstanceOperationCoordinator() {
        this(Clock.systemUTC());
    }

    InstanceOperationCoordinator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

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

    public <T> Result<T> execute(Path instanceRoot, UUID instanceId, OperationJournal.Kind kind,
                                 String description, Operation<T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        UUID operationId = UUID.randomUUID();
        Path journalFile = journalFile(instanceRoot, operationId);
        try (AutoCloseable ignored = acquire(instanceId)) {
            OperationJournal journal = OperationJournal.started(
                    operationId, instanceId, Objects.requireNonNull(kind, "kind"),
                    description, Instant.now(clock));
            journal.write(journalFile);
            T value;
            try {
                value = operation.run(operationId);
            } catch (Exception | Error failure) {
                try {
                    journal.failed(Instant.now(clock), failure).write(journalFile);
                } catch (IOException journalFailure) {
                    failure.addSuppressed(journalFailure);
                }
                throw failure;
            }
            journal.succeeded(Instant.now(clock)).write(journalFile);
            return new Result<>(operationId, value);
        }
    }

    public Path journalFile(Path instanceRoot, UUID operationId) {
        Path root = Objects.requireNonNull(instanceRoot, "instanceRoot").toAbsolutePath().normalize();
        return root.resolve(OPERATIONS_RELATIVE_PATH)
                .resolve(Objects.requireNonNull(operationId, "operationId") + ".json")
                .normalize();
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run(UUID operationId) throws Exception;
    }

    public record Result<T>(UUID operationId, T value) {
        public Result {
            Objects.requireNonNull(operationId, "operationId");
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
