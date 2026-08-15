package com.ecl.game;

import com.ecl.util.GsonProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Persists total and latest-session play duration alongside an instance. */
public final class PlaytimeTracker {
    public synchronized void recordSession(Path instanceRoot, long startedAtMillis, long endedAtMillis) throws IOException {
        long seconds = Math.max(0, (endedAtMillis - startedAtMillis) / 1000);
        Path file = instanceRoot.toAbsolutePath().normalize().resolve(".ecl/config/playtime.json");
        Files.createDirectories(file.getParent());
        JsonObject value = Files.isRegularFile(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
        value.addProperty("totalSeconds", value.has("totalSeconds") ? value.get("totalSeconds").getAsLong() + seconds : seconds);
        value.addProperty("sessionSeconds", seconds);
        value.addProperty("lastPlayedAt", Instant.ofEpochMilli(endedAtMillis).toString());
        Files.writeString(file, GsonProvider.pretty().toJson(value), StandardCharsets.UTF_8);
    }
    public synchronized long totalSeconds(Path instanceRoot) throws IOException {
        Path file = instanceRoot.toAbsolutePath().normalize().resolve(".ecl/config/playtime.json");
        if (!Files.isRegularFile(file)) return 0;
        JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return value.has("totalSeconds") ? value.get("totalSeconds").getAsLong() : 0;
    }
}
