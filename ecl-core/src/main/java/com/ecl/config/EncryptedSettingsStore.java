package com.ecl.config;

import com.ecl.util.CryptoUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles encrypted values inside the settings JSON object. */
final class EncryptedSettingsStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(EncryptedSettingsStore.class);
    private volatile String lastFailureKey;

    boolean set(JsonObject settings, String key, String value) {
        String encryptedKey = "_enc_" + key;
        if (value == null || value.isBlank()) {
            settings.remove(encryptedKey);
            return true;
        }
        String encrypted = CryptoUtil.encrypt(value);
        if (encrypted.isBlank()) {
            return false;
        }
        settings.addProperty(encryptedKey, encrypted);
        return true;
    }

    String get(JsonObject settings, String key) {
        String encryptedKey = "_enc_" + key;
        if (!settings.has(encryptedKey) || settings.get(encryptedKey).isJsonNull()) {
            return null;
        }
        String encrypted;
        try {
            encrypted = settings.get(encryptedKey).getAsString();
        } catch (RuntimeException error) {
            lastFailureKey = key;
            LOGGER.warn("Ignoring unreadable encrypted setting '{}'", key, error);
            return null;
        }
        try {
            return CryptoUtil.decrypt(encrypted);
        } catch (RuntimeException error) {
            lastFailureKey = key;
            LOGGER.warn("Ignoring unreadable encrypted setting '{}'", key, error);
            return null;
        }
    }

    String consumeFailureKey() {
        String key = lastFailureKey;
        lastFailureKey = null;
        return key;
    }
}
