package com.ecl.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for sensitive launcher data (tokens, refresh tokens).
 *
 * <p>The AES key is protected by Windows DPAPI. On non-Windows systems a local AES-GCM wrapper is
 * used so account persistence remains available without loading the Windows-only DPAPI library.
 * The optional {@code ecl.crypto.keyFile} property only redirects the key-file location; it never
 * disables key protection.
 */
public final class CryptoUtil {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;    // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // bits

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
            SecretKey key = CryptoKeyStore.loadOrCreate(true);
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
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException e) {
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
            SecretKey key = CryptoKeyStore.loadOrCreate(false);
            byte[] decoded = Base64.getDecoder().decode(encryptedBase64);
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
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to decrypt sensitive data", e);
        }
    }

    static javax.crypto.SecretKey localWrappingKey(byte[] salt) throws GeneralSecurityException {
        return CryptoKeyStore.localWrappingKey(salt);
    }

    static javax.crypto.SecretKey legacyLocalWrappingKeyV2() throws NoSuchAlgorithmException {
        return CryptoKeyStore.legacyLocalWrappingKeyV2();
    }

    static javax.crypto.SecretKey legacyLocalWrappingKey() throws NoSuchAlgorithmException {
        return CryptoKeyStore.legacyLocalWrappingKey();
    }

    /** Configure the secure user/OS key wrapper used on non-Windows platforms. */
    public static void setKeyProtectionProvider(KeyProtectionProvider provider) {
        CryptoKeyStore.setProtectionProvider(provider);
    }

    /** Reset the cached key (useful for testing). */
    public static void resetKeyCache() {
        CryptoKeyStore.resetCache();
    }
}
