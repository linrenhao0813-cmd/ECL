package com.ecl.auth;

import com.ecl.ECLConfig;
import com.ecl.util.CryptoUtil;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftAccountStoreTest {
    @TempDir
    Path tempDir;

    private Field baseDirField;
    private File previousBaseDir;

    @BeforeEach
    void useTemporaryBaseDirectory() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, tempDir.toFile());
        System.setProperty("ecl.crypto.keyFile", tempDir.resolve("secret.key").toString());
        CryptoUtil.resetKeyCache();
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        CryptoUtil.resetKeyCache();
        System.clearProperty("ecl.crypto.keyFile");
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void storesMultipleAccountsAndEncryptsTokens() throws Exception {
        Path storeFile = tempDir.resolve("accounts.json");
        MicrosoftAccountStore store = new MicrosoftAccountStore(storeFile);

        assertTrue(store.save(new MicrosoftAccountStore.Account(
                "uuid-a", "Alex", "refresh-a", "access-a", 123)));
        assertTrue(store.save(new MicrosoftAccountStore.Account(
                "uuid-b", "Steve", "refresh-b", "access-b", 456)));

        assertEquals(2, store.list().size());
        assertEquals("refresh-a", store.list().get(0).refreshToken());
        String raw = Files.readString(storeFile);
        assertFalse(raw.contains("refresh-a"));
        assertFalse(raw.contains("access-b"));

        assertTrue(store.remove("uuid-a"));
        assertEquals("Steve", store.list().getFirst().username());
    }

    @Test
    void unreadableAccountDoesNotBlockAddingAReplacement() throws Exception {
        Path storeFile = tempDir.resolve("accounts.json");
        Files.writeString(storeFile, """
                [
                  {
                    "uuid": "legacy",
                    "username": "Legacy",
                    "refreshToken": "not-valid-ciphertext",
                    "accessToken": "also-invalid",
                    "accessTokenExpiresAt": 1
                  }
                ]
                """);
        MicrosoftAccountStore store = new MicrosoftAccountStore(storeFile);

        assertTrue(store.save(new MicrosoftAccountStore.Account(
                "new", "NewAccount", "new-refresh", "new-access", 2)));

        String raw = Files.readString(storeFile);
        assertTrue(raw.contains("not-valid-ciphertext"));
        assertTrue(raw.contains("also-invalid"));
        assertFalse(raw.contains("new-refresh"), "replacement credentials stay encrypted");
        assertEquals("NewAccount", store.list().getFirst().username());
    }

    @Test
    void publicStoreMigratesLegacyOnlyOnceWithoutOverwritingUnifiedSession() throws Exception {
        DefaultAccountService unified = new DefaultAccountService();
        unified.save(new AuthAccount(AuthType.MICROSOFT, "same-uuid", "Current", "Current",
                "current-access", "current-refresh", 999, "", true));
        writeLegacyAccount("same-uuid", "Legacy", "legacy-access", "legacy-refresh", 1);

        MicrosoftAccountStore store = new MicrosoftAccountStore();

        assertEquals("current-refresh", store.list().getFirst().refreshToken());
        assertTrue(unified.defaultAccount().orElseThrow().defaultAccount());
        assertFalse(Files.exists(tempDir.resolve("microsoft-accounts.json")));
        assertTrue(Files.exists(tempDir.resolve("microsoft-accounts.json.migrated")));

        assertTrue(store.remove("same-uuid"));
        assertTrue(new MicrosoftAccountStore().list().isEmpty(),
                "archived legacy data must not resurrect a removed account");
    }

    @Test
    void savingExistingMicrosoftAccountPreservesDefaultFlag() {
        DefaultAccountService unified = new DefaultAccountService();
        unified.save(new AuthAccount(AuthType.MICROSOFT, "uuid", "Alex", "Alex",
                "old-access", "old-refresh", 1, "", true));
        MicrosoftAccountStore store = new MicrosoftAccountStore();

        assertTrue(store.save(new MicrosoftAccountStore.Account(
                "uuid", "Alex", "new-refresh", "new-access", 2)));

        AuthAccount saved = unified.defaultAccount().orElseThrow();
        assertTrue(saved.defaultAccount());
        assertEquals("new-refresh", saved.refreshToken());
    }

    private void writeLegacyAccount(String uuid, String username, String accessToken,
                                    String refreshToken, long expiry) throws Exception {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", uuid);
        object.addProperty("username", username);
        object.addProperty("accessToken", CryptoUtil.encrypt(accessToken));
        object.addProperty("refreshToken", CryptoUtil.encrypt(refreshToken));
        object.addProperty("accessTokenExpiresAt", expiry);
        JsonArray array = new JsonArray();
        array.add(object);
        Files.writeString(tempDir.resolve("microsoft-accounts.json"),
                GsonProvider.pretty().toJson(array));
    }
}
