package com.ecl.game;

import com.ecl.util.GsonProvider;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads and atomically writes settings rooted at {@code <instance>/.ecl/config/}. */
public final class InstanceGameSettingsStore {
    public static final String SETTINGS_RELATIVE_PATH = ".ecl/config/instance-game-settings.json";
    private static final Gson GSON = GsonProvider.pretty();

    public InstanceGameSettings load(Path instanceRoot) throws IOException {
        Path file = settingsFile(instanceRoot);
        if (!Files.isRegularFile(file)) {
            return InstanceGameSettings.inherited();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            boolean override = json.has("overrideRunningDirectory")
                    && json.get("overrideRunningDirectory").getAsBoolean();
            String directory = json.has("runningDirectory")
                    ? json.get("runningDirectory").getAsString() : "";
            return new InstanceGameSettings(override, directory);
        } catch (RuntimeException invalidJson) {
            throw new IOException("实例运行目录设置损坏: " + file, invalidJson);
        }
    }

    public void save(Path instanceRoot, InstanceGameSettings settings) throws IOException {
        Path file = settingsFile(instanceRoot);
        Files.createDirectories(file.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("overrideRunningDirectory", settings.overridesRunningDirectory());
        json.addProperty("runningDirectory",
                settings.runningDirectory() == null ? "" : settings.runningDirectory());
        Path temporary = Files.createTempFile(file.getParent(), "instance-settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
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

    public Path settingsFile(Path instanceRoot) {
        return instanceRoot.toAbsolutePath().normalize().resolve(SETTINGS_RELATIVE_PATH).normalize();
    }
}
