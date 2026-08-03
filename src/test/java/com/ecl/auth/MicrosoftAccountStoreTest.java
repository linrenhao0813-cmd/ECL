package com.ecl.auth;

import com.ecl.ECLConfig;
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
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
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
    void updatingAnotherAccountPreservesUndecryptableCiphertext() throws Exception {
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
        assertEquals(2, store.list().size());
    }
}
