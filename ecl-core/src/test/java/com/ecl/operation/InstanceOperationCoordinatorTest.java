package com.ecl.operation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceOperationCoordinatorTest {
    @TempDir
    Path tempDir;

    @Test
    void writesSuccessfulOperationJournal() throws Exception {
        Instant now = Instant.parse("2026-08-24T02:00:00Z");
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator(
                Clock.fixed(now, ZoneOffset.UTC));
        UUID instanceId = UUID.randomUUID();

        InstanceOperationCoordinator.Result<String> result = coordinator.execute(
                tempDir, instanceId, OperationJournal.Kind.AUTO_REPAIR,
                "Lower conflicting heap settings", operationId -> "done");

        OperationJournal journal = OperationJournal.read(
                coordinator.journalFile(tempDir, result.operationId()));
        assertEquals("done", result.value());
        assertEquals(OperationJournal.Status.SUCCEEDED, journal.status());
        assertEquals(instanceId, journal.instanceId());
        assertEquals(OperationJournal.Kind.AUTO_REPAIR, journal.kind());
        assertEquals(now.toString(), journal.startedAt());
        assertEquals(now.toString(), journal.finishedAt());
    }

    @Test
    void recordsFailureAndRethrowsOriginalException() throws Exception {
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();
        UUID instanceId = UUID.randomUUID();

        IOException failure = assertThrows(IOException.class, () -> coordinator.execute(
                tempDir, instanceId, OperationJournal.Kind.BACKUP_RESTORE,
                "Restore snapshot", operationId -> {
                    throw new IOException("verification failed");
                }));

        assertEquals("verification failed", failure.getMessage());
        Path journalFile;
        try (var journals = java.nio.file.Files.list(
                tempDir.resolve(InstanceOperationCoordinator.OPERATIONS_RELATIVE_PATH))) {
            journalFile = journals.findFirst().orElseThrow();
        }
        OperationJournal journal = OperationJournal.read(journalFile);
        assertEquals(OperationJournal.Status.FAILED, journal.status());
        assertEquals(IOException.class.getName(), journal.failureType());
        assertEquals("verification failed", journal.failureMessage());
    }

    @Test
    void serializesOperationsForTheSameInstance() throws Exception {
        InstanceOperationCoordinator coordinator = new InstanceOperationCoordinator();
        UUID instanceId = UUID.randomUUID();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runUnchecked(() -> coordinator.execute(
                    tempDir, instanceId, OperationJournal.Kind.MOD_INSTALL, "first", operationId -> {
                        firstEntered.countDown();
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                        return null;
                    })));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> runUnchecked(() -> coordinator.execute(
                    tempDir, instanceId, OperationJournal.Kind.MODPACK_UPDATE, "second", operationId -> {
                        secondEntered.countDown();
                        return null;
                    })));

            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertFalse(coordinator.isLocked(instanceId));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void runUnchecked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
