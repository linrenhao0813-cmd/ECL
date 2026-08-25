package com.ecl.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.SecureRandom;

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
        System.clearProperty("ecl.crypto.machineId");
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
    void legacyRawKeyIsMigratedToProtectedEncoding() throws Exception {
        Path keyFile = temp.resolve("secret.key");
        Files.write(keyFile, new byte[32]);
        CryptoUtil.resetKeyCache();

        assertThrows(IllegalStateException.class, () -> CryptoUtil.decrypt("not-base64"));
        assertTrue(Files.size(keyFile) > 32);

        CryptoUtil.encrypt("trigger-migration");
        assertTrue(Files.size(keyFile) > 32);
    }

    @Test
    void localWrappingKeyIncludesStableMachineIdentity() throws Exception {
        System.setProperty("ecl.crypto.machineId", "machine-a");
        byte[] firstMachineKey = CryptoUtil.localWrappingKey().getEncoded();

        System.setProperty("ecl.crypto.machineId", "machine-b");
        byte[] secondMachineKey = CryptoUtil.localWrappingKey().getEncoded();

        assertFalse(java.util.Arrays.equals(firstMachineKey, secondMachineKey));
    }

    @Test
    void legacyLocalWrapperIsMigratedWithoutLosingTheAccountKey() throws Exception {
        String originalOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("ecl.crypto.machineId", "migration-machine");
            Path keyFile = temp.resolve("secret.key");
            byte[] accountKey = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(accountKey);
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, CryptoUtil.legacyLocalWrappingKey(),
                    new GCMParameterSpec(128, iv));
            byte[] wrapped = cipher.doFinal(accountKey);
            byte[] header = "ECL-LOCAL-1\n".getBytes(StandardCharsets.US_ASCII);
            Files.write(keyFile, ByteBuffer.allocate(header.length + iv.length + wrapped.length)
                    .put(header).put(iv).put(wrapped).array());
            CryptoUtil.resetKeyCache();

            String encrypted = CryptoUtil.encrypt("migrated-secret");

            assertEquals("migrated-secret", CryptoUtil.decrypt(encrypted));
            byte[] migrated = Files.readAllBytes(keyFile);
            assertTrue(new String(migrated, 0, "ECL-LOCAL-2\n".length(),
                    StandardCharsets.US_ASCII).startsWith("ECL-LOCAL-2"));
        } finally {
            if (originalOsName == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOsName);
            }
        }
    }
}
