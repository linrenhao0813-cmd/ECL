package com.ecl.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        System.setProperty("ecl.crypto.keyFile", temp.resolve("secret.key").toString());
        CryptoUtil.resetKeyCache();
    }

    @AfterEach
    void tearDown() {
        CryptoUtil.resetKeyCache();
        System.clearProperty("ecl.crypto.keyFile");
    }

    @Test
    void encryptDecryptRoundtrip() {
        String original = "my-secret-token-12345";
        String encrypted = CryptoUtil.encrypt(original);
        assertNotNull(encrypted);
        assertFalse(encrypted.isBlank());
        assertNotEquals(original, encrypted);

        String decrypted = CryptoUtil.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void encryptReturnsEmptyForNullInput() {
        assertEquals("", CryptoUtil.encrypt(null));
    }

    @Test
    void encryptReturnsEmptyForBlankInput() {
        assertEquals("", CryptoUtil.encrypt("   "));
    }

    @Test
    void decryptReturnsNullForNullInput() {
        assertNull(CryptoUtil.decrypt(null));
    }

    @Test
    void decryptReturnsNullForBlankInput() {
        assertNull(CryptoUtil.decrypt(""));
    }

    @Test
    void decryptFailsClosedForGarbageInput() {
        assertThrows(IllegalStateException.class,
                () -> CryptoUtil.decrypt("this-is-not-valid-base64!!!"));
    }

    @Test
    void differentPlaintextsProduceDifferentCiphertexts() {
        String enc1 = CryptoUtil.encrypt("secret1");
        String enc2 = CryptoUtil.encrypt("secret2");
        assertNotEquals(enc1, enc2);
    }

    @Test
    void samePlaintextProducesDifferentCiphertextsDueToRandomIV() {
        String enc1 = CryptoUtil.encrypt("same-value");
        String enc2 = CryptoUtil.encrypt("same-value");
        // AES-GCM uses a random IV, so ciphertexts should differ
        assertNotEquals(enc1, enc2);
    }

    @Test
    void encryptDecryptLongString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("data-").append(i).append(",");
        }
        String longStr = sb.toString();
        String encrypted = CryptoUtil.encrypt(longStr);
        String decrypted = CryptoUtil.decrypt(encrypted);
        assertEquals(longStr, decrypted);
    }

    @Test
    void encryptDecryptUnicode() {
        String chinese = "你好世界！🌟测试";
        String encrypted = CryptoUtil.encrypt(chinese);
        String decrypted = CryptoUtil.decrypt(encrypted);
        assertEquals(chinese, decrypted);
    }

    @Test
    void keyCacheWorks() {
        // First call creates and caches the key; second call reuses it
        String encrypted = CryptoUtil.encrypt("test");
        String decrypted = CryptoUtil.decrypt(encrypted);
        assertEquals("test", decrypted);
    }

    @Test
    void explicitPlaintextTestKeyRemainsReadable() throws Exception {
        Path keyFile = temp.resolve("secret.key");
        Files.write(keyFile, new byte[32]);
        CryptoUtil.resetKeyCache();

        assertThrows(IllegalStateException.class, () -> CryptoUtil.decrypt("not-base64"));
        assertEquals(32, Files.size(keyFile));

        CryptoUtil.encrypt("trigger-migration");
        assertEquals(32, Files.size(keyFile));
    }
}
