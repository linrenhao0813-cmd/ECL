package com.ecl.launcher;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interprets the official version manifest without owning its network/cache lifecycle. */
final class VersionCatalogService {
    private static final Set<String> APRIL_FOOLS_VERSION_IDS = Set.of(
            "15w14a",
            "1.RV-Pre1",
            "3D Shareware v1.34",
            "20w14infinite",
            "22w13oneblockatatime",
            "23w13a_or_b",
            "24w14potato",
            "25w14craftmine"
    );

    private VersionCatalogService() {
    }

    static List<String> versions(JsonObject manifest, VersionManager.VersionCategory category) {
        List<String> versions = new ArrayList<>();
        if (manifest == null || !manifest.has("versions")) {
            return versions;
        }
        JsonArray entries = manifest.getAsJsonArray("versions");
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            if (matchesCategory(version, category)) {
                versions.add(JsonUtil.getString(version, "id", ""));
            }
        }
        return versions;
    }

    static Map<String, JsonObject> buildIndex(JsonObject manifest) {
        if (manifest == null || !manifest.has("versions")) {
            return Map.of();
        }
        JsonArray entries = manifest.getAsJsonArray("versions");
        Map<String, JsonObject> index = new HashMap<>(entries.size() * 2);
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            String id = JsonUtil.getString(version, "id", "");
            if (!id.isEmpty()) {
                index.putIfAbsent(id, version);
            }
        }
        return index;
    }

    private static boolean matchesCategory(JsonObject version, VersionManager.VersionCategory category) {
        VersionManager.VersionCategory selected = category == null
                ? VersionManager.VersionCategory.FEATURED : category;
        String type = JsonUtil.getString(version, "type", "");
        return switch (selected) {
            case FEATURED -> "release".equals(type) || "snapshot".equals(type)
                    || isAprilFoolsVersion(version);
            case RELEASE -> "release".equals(type);
            case PREVIEW -> "snapshot".equals(type);
            case APRIL_FOOLS -> isAprilFoolsVersion(version);
            case ALL -> true;
        };
    }

    private static boolean isAprilFoolsVersion(JsonObject version) {
        String releaseTime = JsonUtil.getString(version, "releaseTime", "");
        if ("snapshot".equals(JsonUtil.getString(version, "type", ""))
                && releaseTime.length() >= 10
                && "04-01".equals(releaseTime.substring(5, 10))) {
            return true;
        }
        return APRIL_FOOLS_VERSION_IDS.contains(JsonUtil.getString(version, "id", ""));
    }
}
