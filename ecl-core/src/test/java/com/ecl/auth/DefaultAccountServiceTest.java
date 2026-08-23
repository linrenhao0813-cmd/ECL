package com.ecl.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void unreadableCredentialDoesNotLockAccountManagement() throws Exception {
        System.setProperty("ecl.crypto.keyFile", temp.resolve("secret.key").toString());
        com.ecl.util.CryptoUtil.resetKeyCache();
        Path file = temp.resolve("accounts.json");
        String original = "[{\"type\":\"MICROSOFT\",\"uuid\":\"id\","
                + "\"username\":\"Alex\",\"accessToken\":\"not-ciphertext\"}]";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        DefaultAccountService service = new DefaultAccountService(file, new AuthProviderRegistry());

        AuthAccount saved = service.addOffline("Steve");

        assertEquals("Steve", saved.username());
        assertEquals(1, service.list().size());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("not-ciphertext"));
        assertTrue(service.remove("MICROSOFT:id"));
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("not-ciphertext"));
    }

    @Test
    void malformedIdentityHintCannotAbortAccountListing() throws Exception {
        Path file = temp.resolve("accounts.json");
        Files.writeString(file, "[{\"type\":{},\"uuid\":[],\"username\":\"Broken\"}]");
        DefaultAccountService service = new DefaultAccountService(file, new AuthProviderRegistry());

        assertTrue(service.list().isEmpty());
        service.addOffline("Steve");

        assertEquals(2, JsonParser.parseString(Files.readString(file)).getAsJsonArray().size());
    }
}
