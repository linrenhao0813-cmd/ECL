package com.ecl.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {

    @BeforeEach
    void setUp() {
        CryptoUtil.resetKeyCache();
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
    void decryptReturnsNullForGarbageInput() {
        assertNull(CryptoUtil.decrypt("this-is-not-valid-base64!!!"));
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
}
