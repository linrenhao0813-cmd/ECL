package com.ecl.ui;

import com.ecl.game.InstanceDisplayMetadata;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared read/write access to the instance display metadata store (custom name, favourite, cover).
 * Keeping one cache avoids re-reading the JSON file for every list cell.
 */
final class InstanceDisplayMetadataCache {
    private final MainController controller;
    private Map<String, InstanceDisplayMetadata> entries = new LinkedHashMap<>();

    InstanceDisplayMetadataCache(MainController controller) {
        this.controller = controller;
        reload();
    }

    void reload() {
        try {
            entries = new LinkedHashMap<>(controller.instanceDisplayMetadata().load());
        } catch (IOException error) {
            LauncherUI.LOGGER.warn("Cannot read instance display metadata", error);
            entries = new LinkedHashMap<>();
        }
    }

    InstanceDisplayMetadata get(String profileId) {
        InstanceDisplayMetadata entry = entries.get(profileId);
        return entry == null ? InstanceDisplayMetadata.empty(profileId) : entry;
    }

    /** Persists one entry and keeps the cache in sync. */
    void put(InstanceDisplayMetadata entry) {
        Map<String, InstanceDisplayMetadata> updated = new LinkedHashMap<>(entries);
        if (entry.isMeaningful()) {
            updated.put(entry.profileId(), entry);
        } else {
            updated.remove(entry.profileId());
        }
        try {
            controller.instanceDisplayMetadata().save(updated);
        } catch (IOException error) {
            throw new IllegalStateException("无法保存实例显示设置", error);
        }
        entries = updated;
    }
}
