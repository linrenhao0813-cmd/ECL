package com.ecl.game;

import com.ecl.util.GsonProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/** Persists total and latest-session play duration alongside an instance. */
public final class PlaytimeTracker {
    /** Records a successful game process start immediately, before the process exits. */
    public synchronized void recordLaunch(Path instanceRoot, long startedAtMillis) throws IOException {
        Path file = statsFile(instanceRoot);
        Files.createDirectories(file.getParent());
        JsonObject value = read(file);
        value.addProperty("launchCount", number(value, "launchCount") + 1);
        value.addProperty("lastLaunchedAt", Instant.ofEpochMilli(startedAtMillis).toString());
        write(file, value);
    }

    public synchronized void recordSession(Path instanceRoot, long startedAtMillis, long endedAtMillis)
            throws IOException {
        long seconds = elapsedSeconds(endedAtMillis - startedAtMillis);
        Path file = statsFile(instanceRoot);
        Files.createDirectories(file.getParent());
        JsonObject value = read(file);
        value.addProperty("totalSeconds", number(value, "totalSeconds") + seconds);
        value.addProperty("sessionSeconds", seconds);
        value.addProperty("lastExitedAt", Instant.ofEpochMilli(endedAtMillis).toString());
        write(file, value);
    }

    /** Record a session with a monotonic elapsed duration while retaining wall-clock timestamps. */
    public synchronized void recordSession(Path instanceRoot, long startedAtMillis, long endedAtMillis,
                                            long elapsedNanos) throws IOException {
        long seconds = Math.max(0, elapsedNanos / 1_000_000_000L);
        Path file = statsFile(instanceRoot);
        Files.createDirectories(file.getParent());
        JsonObject value = read(file);
        value.addProperty("totalSeconds", number(value, "totalSeconds") + seconds);
        value.addProperty("sessionSeconds", seconds);
        value.addProperty("lastExitedAt", Instant.ofEpochMilli(endedAtMillis).toString());
        write(file, value);
    }

    private static long elapsedSeconds(long elapsedMillis) {
        return Math.max(0, elapsedMillis / 1000);
    }

    public synchronized long totalSeconds(Path instanceRoot) throws IOException {
        return stats(instanceRoot).totalSeconds();
    }

    public synchronized PlaytimeStats stats(Path instanceRoot) throws IOException {
        JsonObject value = read(statsFile(instanceRoot));
        return new PlaytimeStats(
                number(value, "totalSeconds"),
                number(value, "sessionSeconds"),
                number(value, "launchCount"),
                text(value, "lastLaunchedAt", text(value, "lastPlayedAt", "")),
                text(value, "lastExitedAt", ""));
    }

    private static Path statsFile(Path instanceRoot) {
        return Objects.requireNonNull(instanceRoot, "instanceRoot")
                .toAbsolutePath().normalize().resolve(".ecl/config/playtime.json");
    }

    private static JsonObject read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("游玩统计文件格式无效: " + file, error);
        }
    }

    private static void write(Path file, JsonObject value) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "playtime-", ".json.tmp");
        try {
            Files.writeString(temporary, GsonProvider.pretty().toJson(value), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long number(JsonObject value, String key) {
        try {
            return value.has(key) ? Math.max(0, value.get(key).getAsLong()) : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String text(JsonObject value, String key, String fallback) {
        try {
            return value.has(key) && value.get(key).isJsonPrimitive()
                    ? value.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public record PlaytimeStats(long totalSeconds, long sessionSeconds, long launchCount,
                                String lastLaunchedAt, String lastExitedAt) {
        public PlaytimeStats {
            totalSeconds = Math.max(0, totalSeconds);
            sessionSeconds = Math.max(0, sessionSeconds);
            launchCount = Math.max(0, launchCount);
            lastLaunchedAt = lastLaunchedAt == null ? "" : lastLaunchedAt;
            lastExitedAt = lastExitedAt == null ? "" : lastExitedAt;
        }
    }
}
