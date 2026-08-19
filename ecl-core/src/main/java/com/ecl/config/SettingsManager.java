package com.ecl.config;

import com.ecl.util.CryptoUtil;
import com.ecl.util.GsonProvider;
import com.google.gson.Gson;
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
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages launcher settings persistence.
 */
public class SettingsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsManager.class);
    private static final Gson GSON = GsonProvider.pretty();
    private static final File SETTINGS_FILE = new File(com.ecl.ECLConfig.getBaseDir(), "settings.json");

    private JsonObject settings = new JsonObject();
    private boolean loadAttempted;

    public synchronized void load() {
        loadAttempted = true;
        if (SETTINGS_FILE.exists()) {
            try (Reader reader = Files.newBufferedReader(SETTINGS_FILE.toPath(), StandardCharsets.UTF_8)) {
                settings = JsonParser.parseReader(reader).getAsJsonObject();
                // 兼容迁移：旧键 "versionCategory" → "versionCategory2"
                migrateSettingKey("versionCategory", "versionCategory2");
            } catch (Exception e) {
                LOGGER.warn("Failed to load settings from {}", SETTINGS_FILE, e);
                settings = new JsonObject();
            }
        } else {
            settings = new JsonObject();
        }
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

    /**
     * 批量设置多个键值对。所有设置在同一个同步块内完成，减少锁竞争。
     * 调用后仍需调用 save() 持久化到磁盘。
     *
     * @throws NullPointerException 当 entries、键或值为 null 时抛出
     * @throws IllegalArgumentException 当 entries 中出现不支持的值类型时抛出
    */
    public synchronized void setAll(Map<String, Object> entries) {
        Objects.requireNonNull(entries, "entries");
        ensureLoaded();

        // 先验证全部条目，避免后续条目失败时留下部分更新。
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "settings key");
            Object value = Objects.requireNonNull(entry.getValue(), "settings value for key " + key);
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "不支持的设置值类型: " + value.getClass().getName() + " 用于键: " + key);
            }
        }

        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                settings.addProperty(key, s);
            } else if (value instanceof Number n) {
                settings.addProperty(key, n);
            } else if (value instanceof Boolean b) {
                settings.addProperty(key, b);
            }
        }
    }

    public synchronized void remove(String key) {
        ensureLoaded();
        settings.remove(key);
    }

    // ---- Type-safe API via SettingKey ----

    @SuppressWarnings("unchecked")
    public synchronized <T> T get(SettingKey<T> key) {
        String raw = getString(key.key(), null);
        if (raw == null) {
            return key.defaultValue();
        }
        try {
            if (key.type() == String.class) {
                return (T) raw;
            } else if (key.type() == Integer.class) {
                return (T) Integer.valueOf(Integer.parseInt(raw));
            } else if (key.type() == Long.class) {
                return (T) Long.valueOf(Long.parseLong(raw));
            } else if (key.type() == Boolean.class) {
                return (T) Boolean.valueOf(raw);
            }
        } catch (RuntimeException ignored) {
            LOGGER.warn("Failed to parse setting '{}' as {}", key.key(), key.type().getSimpleName());
        }
        return key.defaultValue();
    }

    public synchronized <T> void set(SettingKey<T> key, T value) {
        if (value == null) {
            remove(key.key());
        } else {
            setString(key.key(), value.toString());
        }
    }

    public synchronized boolean has(SettingKey<?> key) {
        ensureLoaded();
        return settings.has(key.key());
    }

    public synchronized void remove(SettingKey<?> key) {
        remove(key.key());
    }

    // ---- Encrypted storage for sensitive tokens ----

    /**
     * Store a sensitive value encrypted with AES-GCM.
     * If {@code value} is null or empty, the key is removed.
     */
    public synchronized void setEncrypted(String key, String value) {
        if (value == null || value.isBlank()) {
            remove("_enc_" + key);
            return;
        }
        String encrypted = CryptoUtil.encrypt(value);
        if (!encrypted.isBlank()) {
            setString("_enc_" + key, encrypted);
        }
    }

    public synchronized void setEncrypted(SettingKey<String> key, String value) {
        setEncrypted(key.key(), value);
    }

    /**
     * Read and decrypt a value stored with {@link #setEncrypted}.
     * Returns the decrypted plaintext, or {@code null} if not found or decryptable.
     */
    public synchronized String getEncrypted(String key) {
        String encrypted = getString("_enc_" + key, null);
        if (encrypted == null) {
            return null;
        }
        try {
            return CryptoUtil.decrypt(encrypted);
        } catch (RuntimeException error) {
            LOGGER.warn("Ignoring unreadable encrypted setting '{}'", key, error);
            return null;
        }
    }

    public synchronized String getEncrypted(SettingKey<String> key) {
        return getEncrypted(key.key());
    }

    /**
     * Migrate a plaintext value to encrypted storage and remove the plaintext key.
     * Used for one-time migration of existing stored tokens.
     */
    public synchronized void migrateToEncrypted(String key) {
        String plaintext = getString(key, null);
        if (plaintext != null && !plaintext.isBlank()) {
            setEncrypted(key, plaintext);
            remove(key);
        }
    }

    /**
     * 将旧键的值迁移到新键（若旧键存在而新键不存在），然后删除旧键。
     * 用于 settings 键名变更时的平滑升级。
     */
    void migrateSettingKey(String oldKey, String newKey) {
        if (settings.has(oldKey) && !settings.has(newKey)) {
            settings.add(newKey, settings.get(oldKey));
            LOGGER.info("Migrated settings key '{}' to '{}'", oldKey, newKey);
        }
        settings.remove(oldKey);
    }

    private void ensureLoaded() {
        if (!loadAttempted) {
            load();
        }
    }
}
