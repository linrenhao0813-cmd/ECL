package com.ecl.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeTrackerTest {
    @Test
    void recordsLaunchesAndSessionDuration(@TempDir Path instanceRoot) throws Exception {
        PlaytimeTracker tracker = new PlaytimeTracker();

        tracker.recordLaunch(instanceRoot, 1_000);
        tracker.recordSession(instanceRoot, 1_000, 6_500);

        PlaytimeTracker.PlaytimeStats stats = tracker.stats(instanceRoot);
        assertEquals(5, stats.totalSeconds());
        assertEquals(5, stats.sessionSeconds());
        assertEquals(1, stats.launchCount());
        assertEquals("1970-01-01T00:00:01Z", stats.lastLaunchedAt());
        assertEquals("1970-01-01T00:00:06.500Z", stats.lastExitedAt());
    }

    @Test
    void readsLegacyLastPlayedTimestampAsLaunchFallback(@TempDir Path instanceRoot) throws Exception {
        Path statsFile = instanceRoot.resolve(".ecl/config/playtime.json");
        Files.createDirectories(statsFile.getParent());
        Files.writeString(statsFile, "{\"lastPlayedAt\":\"2025-01-02T03:04:05Z\"}");

        PlaytimeTracker.PlaytimeStats stats = new PlaytimeTracker().stats(instanceRoot);

        assertEquals("2025-01-02T03:04:05Z", stats.lastLaunchedAt());
        assertEquals("", stats.lastExitedAt());
    }
}
