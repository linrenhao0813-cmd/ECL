package com.ecl.config;

import com.ecl.config.SettingKey;
import com.ecl.config.SettingsManager;
import com.ecl.util.CryptoUtil;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagerTest {
    @TempDir
    Path tempDir;

    private SettingsManager manager;
    private JsonObject settings;

    @BeforeEach
    void createInMemoryManager() throws Exception {
        System.setProperty("ecl.crypto.keyFile", tempDir.resolve("secret.key").toString());
        CryptoUtil.resetKeyCache();
        manager = new SettingsManager();
        settings = new JsonObject();

        Field settingsField = SettingsManager.class.getDeclaredField("settings");
        settingsField.setAccessible(true);
        settingsField.set(manager, settings);

        Field loadedField = SettingsManager.class.getDeclaredField("loadAttempted");
        loadedField.setAccessible(true);
        loadedField.setBoolean(manager, true);
    }

    @AfterEach
    void clearCryptoTestState() {
        CryptoUtil.resetKeyCache();
        System.clearProperty("ecl.crypto.keyFile");
    }

    @Test
    void setsSupportedValuesInOneBatch() {
        manager.setAll(Map.of("text", "value", "number", 42, "enabled", true));

        assertEquals("value", manager.getString("text", ""));
        assertEquals(42, manager.getInt("number", 0));
        assertEquals(true, manager.getBoolean("enabled", false));
    }

    @Test
    void acceptsAnEmptyBatch() {
        manager.setAll(Map.of());
        assertEquals(0, settings.size());
    }

    @Test
    void rejectsUnsupportedValuesWithoutApplyingEarlierEntries() {
        manager.setString("existing", "original");
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("existing", "changed");
        entries.put("unsupported", new Object());

        assertThrows(IllegalArgumentException.class, () -> manager.setAll(entries));

        assertEquals("original", manager.getString("existing", ""));
        assertFalse(settings.has("unsupported"));
    }

    @Test
    void rejectsNullInputsWithoutChangingSettings() {
        manager.setString("existing", "original");
        assertThrows(NullPointerException.class, () -> manager.setAll(null));

        Map<String, Object> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "value");
        assertThrows(NullPointerException.class, () -> manager.setAll(nullKey));

        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("key", null);
        assertThrows(NullPointerException.class, () -> manager.setAll(nullValue));

        assertEquals("original", manager.getString("existing", ""));
        assertEquals(1, settings.size());
    }

    @Test
    void migratesTheTemporaryVersionCategoryKey() {
        settings.addProperty("versionCategory", "SNAPSHOT");

        manager.migrateSettingKey("versionCategory", "versionCategory2");

        assertEquals("SNAPSHOT", manager.getString("versionCategory2", ""));
        assertFalse(settings.has("versionCategory"));
    }

    @Test
    void corruptSettingsAreBackedUpBeforeReset() throws Exception {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{not-json");
        SettingsManager fileManager = new SettingsManager(file.toFile());

        fileManager.load();

        assertTrue(Files.exists(tempDir.resolve("settings.json.corrupt")));
        assertFalse(Files.exists(file));
        assertEquals("fallback", fileManager.getString("missing", "fallback"));
    }

    // ---- Type-safe SettingKey API ----

    private static final SettingKey<String> KEY_NAME = new SettingKey<>("name", String.class, "Player");
    private static final SettingKey<Integer> KEY_COUNT = new SettingKey<>("count", Integer.class, 0);
    private static final SettingKey<Long> KEY_EXPIRES = new SettingKey<>("expires", Long.class, 0L);
    private static final SettingKey<Boolean> KEY_ENABLED = new SettingKey<>("enabled", Boolean.class, false);

    @Test
    void returnsDefaultWhenKeyNotSet() {
        assertEquals("Player", manager.get(KEY_NAME));
        assertEquals(Integer.valueOf(0), manager.get(KEY_COUNT));
        assertEquals(Long.valueOf(0), manager.get(KEY_EXPIRES));
        assertEquals(false, manager.get(KEY_ENABLED));
    }

    @Test
    void roundtripsStringValue() {
        manager.set(KEY_NAME, "Steve");
        assertEquals("Steve", manager.get(KEY_NAME));
    }

    @Test
    void roundtripsIntegerValue() {
        manager.set(KEY_COUNT, 42);
        assertEquals(Integer.valueOf(42), manager.get(KEY_COUNT));
    }

    @Test
    void roundtripsLongValue() {
        manager.set(KEY_EXPIRES, 123456789L);
        assertEquals(Long.valueOf(123456789L), manager.get(KEY_EXPIRES));
    }

    @Test
    void roundtripsBooleanValue() {
        manager.set(KEY_ENABLED, true);
        assertEquals(true, manager.get(KEY_ENABLED));
    }

    @Test
    void settingNullRemovesKey() {
        manager.set(KEY_NAME, "exists");
        manager.set(KEY_NAME, null);
        assertEquals("Player", manager.get(KEY_NAME));
        assertFalse(manager.has(KEY_NAME));
    }

    @Test
    void hasReturnsTrueOnlyForExistingKey() {
        assertFalse(manager.has(KEY_NAME));
        manager.set(KEY_NAME, "value");
        assertTrue(manager.has(KEY_NAME));
    }

    @Test
    void removeViaSettingKeyClearsValue() {
        manager.set(KEY_NAME, "value");
        manager.remove(KEY_NAME);
        assertEquals("Player", manager.get(KEY_NAME));
    }

    // ---- Encrypted storage ----

    @Test
    void encryptDecryptRoundtrip() {
        String sensitive = "my-super-secret-token-123";
        manager.setEncrypted("testToken", sensitive);
        assertEquals(sensitive, manager.getEncrypted("testToken"));
    }

    @Test
    void setEncryptedNullRemovesKey() {
        manager.setEncrypted("testToken", "value");
        manager.setEncrypted("testToken", null);
        assertNull(manager.getEncrypted("testToken"));
    }

    @Test
    void getEncryptedReturnsNullForMissingKey() {
        assertNull(manager.getEncrypted("nonExistent"));
    }

    @Test
    void getEncryptedReturnsNullForCorruptValue() {
        manager.setString("_enc_broken", "not-valid-base64");

        assertNull(manager.getEncrypted("broken"));
    }

    @Test
    void migrateToEncryptedMovesAndClearsPlaintext() {
        manager.setString("oldToken", "secret-value");
        manager.migrateToEncrypted("oldToken");
        assertEquals("secret-value", manager.getEncrypted("oldToken"));
        assertFalse(settings.has("oldToken"));
    }
}
