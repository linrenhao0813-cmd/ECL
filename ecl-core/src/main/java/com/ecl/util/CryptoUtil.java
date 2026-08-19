package com.ecl.util;

import com.ecl.ECLConfig;
import com.sun.jna.platform.win32.Crypt32Util;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * AES-256-GCM encryption for sensitive launcher data (tokens, refresh tokens).
 *
 * <p>The AES key is protected by Windows DPAPI. A plaintext key file is permitted only when the
 * internal {@code ecl.crypto.keyFile} test override is set.
 */
public final class CryptoUtil {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;    // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // bits
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;
    private static final byte[] DPAPI_HEADER = "ECL-DPAPI-1\n".getBytes(StandardCharsets.US_ASCII);

    private static volatile SecretKey cachedKey;

    private CryptoUtil() {
    }

    /**
     * Encrypt plaintext to a Base64-encoded string.
     * Format: IV (12 bytes) + ciphertext + GCM tag (16 bytes), all Base64-encoded.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        try {
            SecretKey key = getOrCreateKey(true);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return java.util.Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt sensitive data", e);
        }
    }

    /**
     * Decrypt a Base64-encoded ciphertext produced by {@link #encrypt(String)}.
     * Returns the original plaintext. Invalid ciphertext and unavailable keys fail closed.
     */
    public static String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            return null;
        }
        try {
            SecretKey key = getOrCreateKey(false);
            byte[] decoded = java.util.Base64.getDecoder().decode(encryptedBase64);
            if (decoded.length < GCM_IV_LENGTH + 1) {
                throw new IllegalArgumentException("Encrypted value is too short");
            }
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt sensitive data", e);
        }
    }

    private static SecretKey getOrCreateKey(boolean create) throws IOException, NoSuchAlgorithmException {
        SecretKey k = cachedKey;
        if (k != null) {
            return k;
        }
        synchronized (CryptoUtil.class) {
            k = cachedKey;
            if (k != null) {
                return k;
            }
            File keyFile = getKeyFile();
            if (keyFile.exists()) {
                byte[] stored = Files.readAllBytes(keyFile.toPath());
                byte[] encoded = decodeStoredKey(stored);
                if (encoded.length != KEY_SIZE / Byte.SIZE) {
                    throw new IOException("Invalid account encryption key length");
                }
                k = new SecretKeySpec(encoded, KEY_ALGORITHM);
                boolean legacyPlaintext = !allowsPlaintextTestKey() && !startsWith(stored, DPAPI_HEADER);
                if (legacyPlaintext) {
                    writeKeyFile(keyFile.toPath(), encodeStoredKey(encoded));
                }
            } else {
                if (!create) throw new IOException("Account encryption key does not exist");
                k = generateKey();
                byte[] encoded = k.getEncoded();
                Files.createDirectories(keyFile.getParentFile().toPath());
                writeKeyFile(keyFile.toPath(), encodeStoredKey(encoded));
            }
            cachedKey = k;
            return k;
        }
    }

    private static SecretKey generateKey() throws NoSuchAlgorithmException {
        KeyGenerator kg = KeyGenerator.getInstance(KEY_ALGORITHM);
        kg.init(KEY_SIZE, SecureRandom.getInstanceStrong());
        return kg.generateKey();
    }

    private static File getKeyFile() {
        String override = System.getProperty("ecl.crypto.keyFile", "");
        if (!override.isBlank()) return new File(override);
        return new File(ECLConfig.getBaseDir(), ".secret.key");
    }

    private static byte[] encodeStoredKey(byte[] key) {
        if (allowsPlaintextTestKey()) return key.clone();
        byte[] protectedKey = Crypt32Util.cryptProtectData(key);
        ByteBuffer buffer = ByteBuffer.allocate(DPAPI_HEADER.length + protectedKey.length);
        buffer.put(DPAPI_HEADER);
        buffer.put(protectedKey);
        return buffer.array();
    }

    private static byte[] decodeStoredKey(byte[] stored) throws IOException {
        if (startsWith(stored, DPAPI_HEADER)) {
            try {
                return Crypt32Util.cryptUnprotectData(
                        Arrays.copyOfRange(stored, DPAPI_HEADER.length, stored.length));
            } catch (RuntimeException failure) {
                throw new IOException("Unable to unlock account key with Windows DPAPI", failure);
            }
        }
        return stored.clone();
    }

    private static boolean allowsPlaintextTestKey() {
        return !System.getProperty("ecl.crypto.keyFile", "").isBlank();
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private static void writeKeyFile(Path target, byte[] value) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, ".secret-key-", ".tmp");
        try {
            Files.write(temp, value);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Reset the cached key (useful for testing). */
    public static void resetKeyCache() {
        cachedKey = null;
    }
}
