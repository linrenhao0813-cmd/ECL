package com.ecl.launch;

import com.ecl.util.FileUtil;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable identity of a launched game process, used after the launcher itself has exited. */
public final class GameProcessMarker {
    public static final String MARKER_RELATIVE_PATH = ".ecl/game-process.json";
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final long MAX_MARKER_BYTES = 16 * 1024;

    private GameProcessMarker() {
    }

    public static void record(Path gameDirectory, ProcessHandle process) throws IOException {
        Objects.requireNonNull(process, "process");
        if (!process.isAlive()) {
            throw new IOException("游戏进程在写入运行标记前已经退出");
        }
        Marker marker = Marker.from(process);
        Path target = markerPath(gameDirectory);
        Path parent = target.getParent();
        Files.createDirectories(parent);
        FileUtil.validateExistingAncestors(normalizeRoot(gameDirectory), target);
        Path temporary = Files.createTempFile(parent, ".game-process-", ".tmp");
        try {
            Files.writeString(temporary, GsonProvider.pretty().toJson(marker.toJson()),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static boolean isRunning(Path gameDirectory) throws IOException {
        Optional<Marker> marker = read(gameDirectory);
        if (marker.isEmpty()) return false;
        Optional<ProcessHandle> process = ProcessHandle.of(marker.get().pid());
        return process.isPresent() && process.get().isAlive() && marker.get().matches(process.get());
    }

    public static void clear(Path gameDirectory, ProcessHandle process) throws IOException {
        Objects.requireNonNull(process, "process");
        Optional<Marker> marker = read(gameDirectory);
        if (marker.isPresent() && marker.get().matches(process)) {
            Files.deleteIfExists(markerPath(gameDirectory));
        }
    }

    public static Path markerPath(Path gameDirectory) throws IOException {
        Path root = normalizeRoot(gameDirectory);
        Path target = root.resolve(MARKER_RELATIVE_PATH).normalize();
        FileUtil.validateExistingAncestors(root, target);
        return target;
    }

    private static Optional<Marker> read(Path gameDirectory) throws IOException {
        Path file = markerPath(gameDirectory);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.size(file) > MAX_MARKER_BYTES) {
            throw new IOException("游戏进程运行标记过大");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("游戏进程运行标记必须是 JSON 对象");
            return Optional.of(Marker.fromJson(parsed.getAsJsonObject()));
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException invalid) {
            throw new IOException("游戏进程运行标记损坏", invalid);
        }
    }

    private static Path normalizeRoot(Path gameDirectory) {
        return Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
    }

    private record Marker(int schemaVersion, long pid, long startedAtEpochMillis) {
        private Marker {
            if (schemaVersion != CURRENT_SCHEMA_VERSION || pid <= 0 || startedAtEpochMillis < 0) {
                throw new IllegalArgumentException("无效的游戏进程运行标记");
            }
        }

        static Marker from(ProcessHandle process) {
            long startedAt = process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            return new Marker(CURRENT_SCHEMA_VERSION, process.pid(), startedAt);
        }

        static Marker fromJson(JsonObject json) {
            return new Marker(required(json, "schemaVersion").getAsInt(),
                    required(json, "pid").getAsLong(),
                    required(json, "startedAtEpochMillis").getAsLong());
        }

        boolean matches(ProcessHandle process) {
            if (process.pid() != pid) return false;
            Optional<Instant> actualStart = process.info().startInstant();
            return startedAtEpochMillis == 0 || actualStart.isEmpty()
                    || actualStart.get().toEpochMilli() == startedAtEpochMillis;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("schemaVersion", schemaVersion);
            json.addProperty("pid", pid);
            json.addProperty("startedAtEpochMillis", startedAtEpochMillis);
            return json;
        }

        private static JsonElement required(JsonObject json, String name) {
            JsonElement value = json == null ? null : json.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                throw new IllegalArgumentException("游戏进程运行标记缺少字段: " + name);
            }
            return value;
        }
    }
}
