package com.ecl.ui;

import com.ecl.launch.GameProcessMarker;
import com.ecl.util.InstanceOperationLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLaunchLockTest {
    @TempDir Path directory;

    @Test
    void markerPublishedAfterPreflightBlocksLaunchAndReleasesTheLease() throws Exception {
        assertFalse(GameProcessMarker.isRunning(directory));
        // The previous launcher publishes a durable marker, then releases its OS lock on exit.
        try (InstanceOperationLease previous = InstanceOperationLease.tryAcquire(directory)) {
            assertNotNull(previous);
            GameProcessMarker.record(directory, ProcessHandle.current());
        }
        assertTrue(GameProcessMarker.isRunning(directory));

        assertThrows(IOException.class, () -> {
            try (var unexpected = GameLaunchCoordinator.acquireGameLock(directory, "test")) {
                assertNotNull(unexpected);
            }
        });
        try (var available = InstanceOperationLease.tryAcquire(directory)) {
            assertNotNull(available, "rejected launch must release the acquired lease");
        }
        assertTrue(GameProcessMarker.isRunning(directory), "rejecting a launch must retain the live marker");
    }

    @Test
    void malformedMarkerAfterPreflightFailsClosedAndReleasesTheLease() throws Exception {
        assertFalse(GameProcessMarker.isRunning(directory));
        Path marker = GameProcessMarker.markerPath(directory);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "broken marker");
        assertThrows(IOException.class, () -> {
            try (var unexpected = GameLaunchCoordinator.acquireGameLock(directory, "test")) {
                assertNotNull(unexpected);
            }
        });
        try (var available = InstanceOperationLease.tryAcquire(directory)) {
            assertNotNull(available);
        }
    }

    @Test
    void stoppedInstanceRetainsExclusiveLeaseUntilCallerClosesIt() throws Exception {
        try (var lease = GameLaunchCoordinator.acquireGameLock(directory, "test")) {
            assertNotNull(lease);
            assertNull(InstanceOperationLease.tryAcquire(directory));
            assertThrows(IOException.class, () -> GameLaunchCoordinator.acquireGameLock(directory, "test"));
        }
        try (var available = InstanceOperationLease.tryAcquire(directory)) {
            assertNotNull(available);
        }
    }
}
