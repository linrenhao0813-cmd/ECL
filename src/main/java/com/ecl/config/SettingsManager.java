package com.ecl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages launcher settings persistence.
 */
public class SettingsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SETTINGS_FILE = new File(com.ecl.ECLConfig.getBaseDir(), "settings.json");

    private JsonObject settings = new JsonObject();
    private boolean loaded;

    public synchronized void load() {
        if (SETTINGS_FILE.exists()) {
            try (Reader reader = Files.newBufferedReader(SETTINGS_FILE.toPath(), StandardCharsets.UTF_8)) {
                settings = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                LOGGER.warn("Failed to load settings from {}", SETTINGS_FILE, e);
                settings = new JsonObject();
            }
        } else {
            settings = new JsonObject();
        }
        loaded = true;
    }

    public synchronized boolean save() {
        ensureLoaded();
        Path tempFile = null;
        try {
            File parent = SETTINGS_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            Path target = SETTINGS_FILE.toPath();
            Path parentPath = target.toAbsolutePath().getParent();
            tempFile = Files.createTempFile(parentPath, "settings-", ".json.tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(settings, writer);
            }
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save settings to {}", SETTINGS_FILE, e);
            return false;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    LOGGER.debug("Failed to remove temporary settings file {}", tempFile, e);
                }
            }
        }
    }

    public synchronized String getString(String key, String defaultValue) {
        ensureLoaded();
        if (settings.has(key)) {
            try {
                return settings.get(key).getAsString();
            } catch (RuntimeException e) {
                LOGGER.warn("Invalid string setting for key {}", key, e);
            }
        }
        return defaultValue;
    }

    public synchronized void setString(String key, String value) {
        ensureLoaded();
        settings.addProperty(key, value);
    }

    public synchronized int getInt(String key, int defaultValue) {
        ensureLoaded();
        if (settings.has(key)) {
            try {
                return settings.get(key).getAsInt();
            } catch (RuntimeException e) {
                LOGGER.warn("Invalid integer setting for key {}", key, e);
            }
        }
        return defaultValue;
    }

    public synchronized void setInt(String key, int value) {
        ensureLoaded();
        settings.addProperty(key, value);
    }

    public synchronized long getLong(String key, long defaultValue) {
        ensureLoaded();
        if (settings.has(key)) {
            try {
                return settings.get(key).getAsLong();
            } catch (RuntimeException e) {
                LOGGER.warn("Invalid long setting for key {}", key, e);
            }
        }
        return defaultValue;
    }

    public synchronized void setLong(String key, long value) {
        ensureLoaded();
        settings.addProperty(key, value);
    }

    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        ensureLoaded();
        if (settings.has(key)) {
            try {
                return settings.get(key).getAsBoolean();
            } catch (RuntimeException e) {
                LOGGER.warn("Invalid boolean setting for key {}", key, e);
            }
        }
        return defaultValue;
    }

    public synchronized void setBoolean(String key, boolean value) {
        ensureLoaded();
        settings.addProperty(key, value);
    }

    public synchronized void remove(String key) {
        ensureLoaded();
        settings.remove(key);
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }
}
