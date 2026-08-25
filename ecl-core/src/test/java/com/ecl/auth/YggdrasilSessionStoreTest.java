package com.ecl.auth;

import com.ecl.util.CryptoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YggdrasilSessionStoreTest {
    @TempDir
    Path temp;

    @AfterEach
    void clearCryptoKeyOverride() {
        CryptoUtil.resetKeyCache();
        System.clearProperty("ecl.crypto.keyFile");
    }

    @Test
    void persistsTokensWithoutPersistingAPassword() throws Exception {
        System.setProperty("ecl.crypto.keyFile", temp.resolve("secret.key").toString());
        CryptoUtil.resetKeyCache();
        Path accountsFile = temp.resolve("accounts.json");
        AccountService accounts = new DefaultAccountService(accountsFile, new AuthProviderRegistry());
        YggdrasilSessionStore sessions = new YggdrasilSessionStore(accounts);
        YggdrasilAuth authenticated = new YggdrasilAuth("https://auth.example.invalid/api/yggdrasil",
                "Player", "player-uuid", "access-secret", "client-secret");

        sessions.save("https://auth.example.invalid/api/yggdrasil", authenticated);

        String raw = Files.readString(accountsFile, StandardCharsets.UTF_8);
        assertFalse(raw.contains("access-secret"));
        assertFalse(raw.contains("client-secret"));
        assertFalse(raw.toLowerCase().contains("password"));
        YggdrasilAuth restored = sessions.restore(
                "https://auth.example.invalid/api/yggdrasil/", "player").orElseThrow();
        assertEquals("access-secret", restored.getAccessToken());
        assertEquals("client-secret", restored.getClientToken());
        assertTrue(restored.isLoggedIn());
    }
}
