package com.ecl.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameProcessMarkerTest {
    @Test
    void survivesIndependentChecksAndClearsOnlyForTheRecordedProcess(@TempDir Path gameDirectory)
            throws Exception {
        ProcessHandle current = ProcessHandle.current();
        GameProcessMarker.record(gameDirectory, current);

        assertTrue(GameProcessMarker.isRunning(gameDirectory));
        assertTrue(Files.isRegularFile(GameProcessMarker.markerPath(gameDirectory)));

        GameProcessMarker.clear(gameDirectory, current);
        assertFalse(GameProcessMarker.isRunning(gameDirectory));
    }

    @Test
    void failsClosedForMalformedMarkers(@TempDir Path gameDirectory) throws Exception {
        Path marker = GameProcessMarker.markerPath(gameDirectory);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "{not json");

        assertThrows(java.io.IOException.class,
                () -> GameProcessMarker.isRunning(gameDirectory));
    }
}
