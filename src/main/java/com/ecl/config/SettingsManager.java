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
import java.nio.file.Files;

/**
 * Manages launcher settings persistence.
 */
public class SettingsManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SETTINGS_FILE = new File(com.ecl.ECLConfig.getBaseDir(), "settings.json");

    private JsonObject settings;

    public void load() {
        if (SETTINGS_FILE.exists()) {
            try (Reader reader = Files.newBufferedReader(SETTINGS_FILE.toPath(), StandardCharsets.UTF_8)) {
                settings = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException e) {
                settings = new JsonObject();
            }
        } else {
            settings = new JsonObject();
        }
    }

    public void save() {
        try {
            File parent = SETTINGS_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (Writer writer = Files.newBufferedWriter(SETTINGS_FILE.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getString(String key, String defaultValue) {
        if (settings != null && settings.has(key)) {
            return settings.get(key).getAsString();
        }
        return defaultValue;
    }

    public void setString(String key, String value) {
        if (settings == null) {
            load();
        }
        settings.addProperty(key, value);
    }

    public int getInt(String key, int defaultValue) {
        if (settings != null && settings.has(key)) {
            return settings.get(key).getAsInt();
        }
        return defaultValue;
    }

    public void setInt(String key, int value) {
        if (settings == null) {
            load();
        }
        settings.addProperty(key, value);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (settings != null && settings.has(key)) {
            return settings.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    public void setBoolean(String key, boolean value) {
        if (settings == null) {
            load();
        }
        settings.addProperty(key, value);
    }

    public void remove(String key) {
        if (settings != null) {
            settings.remove(key);
        }
    }
}