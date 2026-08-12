package com.ecl.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultAccountServiceTest {
    @TempDir
    Path temp;

    @AfterEach
    void clearCryptoKeyOverride() {
        com.ecl.util.CryptoUtil.resetKeyCache();
        System.clearProperty("ecl.crypto.keyFile");
    }

    @Test
    void persistsMultipleAccountTypesWithoutPlaintextCredentials() throws Exception {
        System.setProperty("ecl.crypto.keyFile", temp.resolve("secret.key").toString());
        com.ecl.util.CryptoUtil.resetKeyCache();
        Path file = temp.resolve("accounts.json");
        DefaultAccountService service = new DefaultAccountService(file, new AuthProviderRegistry());
        AuthAccount offline = service.addOffline("Steve");
        AuthAccount microsoft = new AuthAccount(AuthType.MICROSOFT, "uuid", "Alex", "Alex",
                "access-secret", "refresh-secret", 1234, "", false);
        service.save(microsoft);

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(raw.contains("access-secret"));
        assertFalse(raw.contains("refresh-secret"));
        assertEquals(2, service.list().size());
        assertEquals(offline.identity(), service.defaultAccount().orElseThrow().identity());
        assertInstanceOf(OfflineAuth.class, service.createProvider(offline));
        assertInstanceOf(MicrosoftAuth.class, service.createProvider(service.list().get(1)));

        service.setDefault(microsoft.identity());
        assertTrue(service.defaultAccount().orElseThrow().defaultAccount());
        assertEquals("Alex", service.defaultAccount().orElseThrow().username());
    }

    @Test
    void unreadableCredentialAbortsSaveAndPreservesOriginalFile() throws Exception {
        System.setProperty("ecl.crypto.keyFile", temp.resolve("secret.key").toString());
        com.ecl.util.CryptoUtil.resetKeyCache();
        Path file = temp.resolve("accounts.json");
        String original = "[{\"type\":\"MICROSOFT\",\"uuid\":\"id\","
                + "\"username\":\"Alex\",\"accessToken\":\"not-ciphertext\"}]";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        DefaultAccountService service = new DefaultAccountService(file, new AuthProviderRegistry());

        assertThrows(com.ecl.exception.AuthException.class, () -> service.addOffline("Steve"));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }
}
