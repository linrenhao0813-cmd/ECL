package com.ecl.config;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal migration helpers kept separate from ordinary settings persistence. */
final class SettingsMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsMigration.class);

    private SettingsMigration() {
    }

    static void migrateSettingKey(JsonObject settings, String oldKey, String newKey) {
        if (settings.has(oldKey) && !settings.has(newKey)) {
            settings.add(newKey, settings.get(oldKey));
            LOGGER.info("Migrated settings key '{}' to '{}'", oldKey, newKey);
        }
        settings.remove(oldKey);
    }

    static void migrateToEncrypted(SettingsManager manager, String key) {
        String plaintext = manager.getString(key, null);
        if (plaintext != null && !plaintext.isBlank()) {
            manager.setEncrypted(key, plaintext);
            manager.remove(key);
        }
    }
}
