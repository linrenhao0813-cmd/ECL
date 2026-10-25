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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagerTest {
    @Test
    void mergesIndependentChangesAndExplicitDeletesAcrossManagers() throws Exception {
        Path file = tempDir.resolve("shared-settings.json");
        Files.writeString(file, "{\"deleted\":\"old\",\"same\":\"old\"}");
        SettingsManager first = new SettingsManager(file.toFile());
        SettingsManager second = new SettingsManager(file.toFile());
        first.load();
        second.load();
        first.setString("directory", "new-directory");
        first.setString("same", "first");
        assertTrue(first.save());
        second.remove("deleted");
        second.setString("same", "second");
        assertTrue(second.save());
        SettingsManager loaded = new SettingsManager(file.toFile());
        assertEquals("new-directory", loaded.getString("directory", "missing"));
        assertEquals("second", loaded.getString("same", "missing"));
        assertEquals("missing", loaded.getString("deleted", "missing"));
        assertTrue(first.save());
        assertEquals("second", new SettingsManager(file.toFile()).getString("same", "missing"));
    }

    @Test
    void lockedSaveRetainsChangesForRetryIncludingEncryptedMigration() throws Exception {
        Path file = tempDir.resolve("locked-settings.json");
        Files.writeString(file, "{\"legacy\":\"secret\"}");
        SettingsManager first = new SettingsManager(file.toFile());
        first.migrateToEncrypted("legacy");
        try (var lease = com.ecl.util.FileLockLease.tryAcquire(file.resolveSibling(file.getFileName() + ".lock"))) {
            assertFalse(first.save());
        }
        SettingsManager second = new SettingsManager(file.toFile());
        second.setString("unrelated", "preserve");
        assertTrue(second.save());
        assertTrue(first.save());
        SettingsManager loaded = new SettingsManager(file.toFile());
        assertEquals("secret", loaded.getEncrypted("legacy"));
        assertEquals("missing", loaded.getString("legacy", "missing"));
        assertEquals("preserve", loaded.getString("unrelated", "missing"));
    }

    @Test
    void encryptedPrefixRemovalPreservesOtherManagersSecrets() throws Exception {
        Path file = tempDir.resolve("encrypted-settings.json");
        SettingsManager first = new SettingsManager(file.toFile());
        first.setEncrypted("old.token", "old");
        assertTrue(first.save());
        SettingsManager second = new SettingsManager(file.toFile());
        second.load();
        first.setEncrypted("new.token", "new");
        assertTrue(first.save());
        assertTrue(second.removeEncryptedByPrefix("old."));
        assertTrue(second.save());
        SettingsManager loaded = new SettingsManager(file.toFile());
        assertNull(loaded.getEncrypted("old.token"));
        assertEquals("new", loaded.getEncrypted("new.token"));
    }

    @Test
    void revertingAFailedFormChangeDoesNotOverwriteAnotherManagersUpdate() throws Exception {
        Path file = tempDir.resolve("rollback-settings.json");
        Files.writeString(file, "{\"same\":\"old\"}");
        SettingsManager first = new SettingsManager(file.toFile());
        first.load();
        first.setString("same", "unsaved");
        try (var lease = com.ecl.util.FileLockLease.tryAcquire(file.resolveSibling(file.getFileName() + ".lock"))) {
            assertFalse(first.save());
        }
        first.setString("same", "old");
        SettingsManager second = new SettingsManager(file.toFile());
        second.setString("same", "other-process");
        assertTrue(second.save());
        first.setBoolean("unrelated", true);
        assertTrue(first.save());
        assertEquals("other-process", new SettingsManager(file.toFile()).getString("same", "missing"));
    }

    @Test
    void corruptionAfterLoadingIsPreservedAndPendingChangesCanRetry() throws Exception {
        Path file = tempDir.resolve("corrupted-after-load.json");
        Files.writeString(file, "{}");
        SettingsManager first = new SettingsManager(file.toFile());
        first.setString("pending", "keep");
        Files.writeString(file, "{broken");
        assertFalse(first.save());
        assertEquals("{broken", Files.readString(file));
        Files.writeString(file, "{\"external\":\"preserve\"}");
        assertTrue(first.save());
        SettingsManager loaded = new SettingsManager(file.toFile());
        assertEquals("keep", loaded.getString("pending", "missing"));
        assertEquals("preserve", loaded.getString("external", "missing"));
    }

    @Test
    void corruptLoadWhileLockedCannotArchiveTheFileAndCanRetryAfterRelease() throws Exception {
        Path file = tempDir.resolve("busy-corrupt.json");
        Files.writeString(file, "{broken");
        SettingsManager first = new SettingsManager(file.toFile());
        try (var lease = com.ecl.util.FileLockLease.tryAcquire(file.resolveSibling(file.getFileName() + ".lock"))) {
            assertThrows(IllegalStateException.class, first::load);
            assertThrows(IllegalStateException.class, () -> first.setString("pending", "unsafe"));
            assertEquals("{broken", Files.readString(file));
            assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
        }
        Files.writeString(file, "{\"external\":\"valid\"}");
        first.setString("pending", "safe");
        assertTrue(first.save());
        assertEquals("valid", new SettingsManager(file.toFile()).getString("external", "missing"));
    }

    @Test
    void savingOneKeyDoesNotRevertAnotherLaunchersUnrelatedKey() throws Exception {
        Path file = tempDir.resolve("two-launchers.json");
        Files.writeString(file, "{\"gameDir\":\"old-dir\",\"language\":\"zh_CN\"}");
        SettingsManager launcherA = new SettingsManager(file.toFile());
        SettingsManager launcherB = new SettingsManager(file.toFile());
        launcherA.load();
        launcherB.load();

        launcherA.setString("gameDir", "new-dir");
        assertTrue(launcherA.save());
        // Launcher B only changes the language but still caches the stale directory it loaded.
        launcherB.setString("language", "en");
        assertTrue(launcherB.save());

        SettingsManager reloaded = new SettingsManager(file.toFile());
        assertEquals("new-dir", reloaded.getString("gameDir", "missing"),
                "saving an unrelated key must not restore a stale cached directory");
        assertEquals("en", reloaded.getString("language", "missing"));
    }

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
    void reportsUnreadableEncryptedSettingsForUserRecovery() {
        settings.addProperty("_enc_microsoftRefreshToken", "not-a-valid-ciphertext");

        assertNull(manager.getEncrypted("microsoftRefreshToken"));
        assertEquals("microsoftRefreshToken", manager.consumeUnreadableEncryptedSetting());
        assertNull(manager.consumeUnreadableEncryptedSetting());
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
    void loadingDoesNotCreateAnUnusedVersionCategoryKey() throws Exception {
        Path file = tempDir.resolve("legacy-category.json");
        Files.writeString(file, "{\"versionCategory\":\"SNAPSHOT\"}");
        SettingsManager loaded = new SettingsManager(file.toFile());
        loaded.load();
        assertEquals("absent", loaded.getString("versionCategory2", "absent"));
        assertEquals("SNAPSHOT", loaded.getString("versionCategory", ""));
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

    @Test
    void repeatedCorruptionKeepsEveryBackupInsteadOfOverwritingTheFirst() throws Exception {
        Path file = tempDir.resolve("settings.json");
        Path firstBackup = tempDir.resolve("settings.json.corrupt");
        Files.writeString(file, "{not-json");
        new SettingsManager(file.toFile()).load();
        assertEquals("{not-json", Files.readString(firstBackup));

        Files.writeString(file, "{also-not-json");
        new SettingsManager(file.toFile()).load();

        assertEquals("{not-json", Files.readString(firstBackup),
                "The earlier damaged copy must be preserved");
        try (var entries = Files.list(tempDir)) {
            List<Path> backups = entries.filter(path -> path.getFileName().toString()
                    .startsWith("settings.json.corrupt")).toList();
            assertEquals(2, backups.size(), "Each corruption gets its own backup file");
            assertTrue(backups.stream().anyMatch(path -> path.equals(firstBackup)));
            assertFalse(backups.stream().allMatch(path -> path.equals(firstBackup)),
                    "The second corruption must create an additional backup");
        }
    }

    @Test
    void uniqueCorruptBackupFallsBackToATimestampedName() throws Exception {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(tempDir.resolve("settings.json.corrupt"), "taken");

        Path backup = SettingsManager.uniqueCorruptBackup(file);

        assertFalse(backup.equals(tempDir.resolve("settings.json.corrupt")));
        assertTrue(backup.getFileName().toString().startsWith("settings.json.corrupt-"));
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
    void removeEncryptedByPrefixClearsAllMatchingSecretsOnly() {
        manager.setEncrypted("yggdrasilPassword", "legacy");
        manager.setEncrypted("yggdrasilPassword.account-a", "secret-a");
        manager.setEncrypted("microsoftRefreshToken", "refresh");

        assertTrue(manager.removeEncryptedByPrefix("yggdrasilPassword"));

        assertNull(manager.getEncrypted("yggdrasilPassword"));
        assertNull(manager.getEncrypted("yggdrasilPassword.account-a"));
        assertEquals("refresh", manager.getEncrypted("microsoftRefreshToken"));
        assertFalse(manager.removeEncryptedByPrefix("yggdrasilPassword"));
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

    @Test
    void migrateToEncryptedReturnsFalseForMissingValue() {
        assertFalse(manager.migrateToEncrypted("missingToken"));
        assertNull(manager.getEncrypted("missingToken"));
    }

    @Test
    void migrateToEncryptedRemovesBlankValueWithoutReplacingEncryptedValue() {
        manager.setEncrypted("oldToken", "saved-secret");
        manager.setString("oldToken", " ");

        assertTrue(manager.migrateToEncrypted("oldToken"));
        assertFalse(settings.has("oldToken"));
        assertEquals("saved-secret", manager.getEncrypted("oldToken"));
    }
}
