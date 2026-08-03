package com.ecl.util;

import com.ecl.ECLConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM encryption for sensitive launcher data (tokens, refresh tokens).
 *
 * <p>The encryption key is derived from a machine-specific seed and stored
 * in the launcher data directory. If the key file is lost (e.g. user clears
 * launcher data), previously encrypted tokens become unrecoverable.
 */
public final class CryptoUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(CryptoUtil.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;    // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // bits
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;

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
            SecretKey key = getOrCreateKey();
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
            LOGGER.error("Failed to encrypt sensitive data", e);
            return "";
        }
    }

    /**
     * Decrypt a Base64-encoded ciphertext produced by {@link #encrypt(String)}.
     * Returns the original plaintext, or {@code null} if decryption fails.
     */
    public static String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            return null;
        }
        try {
            SecretKey key = getOrCreateKey();
            byte[] decoded = java.util.Base64.getDecoder().decode(encryptedBase64);
            if (decoded.length < GCM_IV_LENGTH + 1) {
                return null;
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
            LOGGER.warn("Failed to decrypt sensitive data (key may have changed)", e);
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws IOException, NoSuchAlgorithmException {
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
                byte[] encoded = Files.readAllBytes(keyFile.toPath());
                k = new SecretKeySpec(encoded, KEY_ALGORITHM);
            } else {
                k = generateKey();
                byte[] encoded = k.getEncoded();
                Files.createDirectories(keyFile.getParentFile().toPath());
                Files.write(keyFile.toPath(), encoded);
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
        return new File(ECLConfig.getBaseDir(), ".secret.key");
    }

    /** Reset the cached key (useful for testing). */
    static void resetKeyCache() {
        cachedKey = null;
    }
}
