package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class VersionManager {
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

    private JsonObject manifest;

    public enum VersionCategory {
        FEATURED("正式+预览+愚人节"),
        RELEASE("正式版"),
        PREVIEW("预览版/快照"),
        APRIL_FOOLS("愚人节版"),
        ALL("全部版本");

        private final String label;

        VersionCategory(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public void refresh() throws IOException {
        File cache = new File(ECLConfig.getVersionsDir(), "version_manifest.json");
        try {
            manifest = HttpUtil.getJsonWithMirrors(ECLConfig.MC_VERSION_MANIFEST_URL, null);
            HttpUtil.writeJson(cache, manifest);
        } catch (IOException networkError) {
            manifest = loadCachedManifest();
            if (manifest == null) {
                throw networkError;
            }
        }
    }

    public List<String> getReleaseVersions() {
        return getVersions(VersionCategory.RELEASE);
    }

    public List<String> getPreviewVersions() {
        return getVersions(VersionCategory.PREVIEW);
    }

    public List<String> getAprilFoolsVersions() {
        return getVersions(VersionCategory.APRIL_FOOLS);
    }

    public List<String> getVersions(VersionCategory category) {
        ensureManifestLoaded();
        List<String> versions = new ArrayList<>();
        if (manifest == null) {
            return versions;
        }

        JsonArray arr = manifest.getAsJsonArray("versions");
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            if (matchesCategory(v, category)) {
                versions.add(getString(v, "id"));
            }
        }
        return versions;
    }

    public List<String> getAllVersions() {
        return getVersions(VersionCategory.ALL);
    }

    public String getVersionUrl(String versionId) {
        ensureManifestLoaded();
        JsonObject version = findVersion(versionId);
        return version == null ? null : getString(version, "url");
    }

    public String getVersionType(String versionId) {
        ensureManifestLoaded();
        JsonObject version = findVersion(versionId);
        return version == null ? "" : getString(version, "type");
    }

    public boolean isReleaseOrSnapshot(String versionId) {
        String type = getVersionType(versionId);
        return "release".equals(type) || "snapshot".equals(type);
    }

    public boolean isVersionDownloaded(String versionId) {
        File json = new File(ECLConfig.getVersionsDir(), versionId + "/" + versionId + ".json");
        File jar = new File(ECLConfig.getVersionsDir(), versionId + "/" + versionId + ".jar");
        return json.exists() && jar.exists();
    }

    public JsonObject loadVersionJson(String versionId) throws IOException {
        File json = new File(ECLConfig.getVersionsDir(), versionId + "/" + versionId + ".json");
        if (json.exists()) {
            return HttpUtil.readJson(json);
        }
        return null;
    }

    private void ensureManifestLoaded() {
        if (manifest == null) {
            manifest = loadCachedManifest();
        }
    }

    private JsonObject loadCachedManifest() {
        File cache = new File(ECLConfig.getVersionsDir(), "version_manifest.json");
        if (!cache.exists()) {
            return null;
        }
        try {
            return HttpUtil.readJson(cache);
        } catch (IOException ignored) {
            return null;
        }
    }

    private JsonObject findVersion(String versionId) {
        if (manifest == null || versionId == null || versionId.isBlank()) {
            return null;
        }
        JsonArray arr = manifest.getAsJsonArray("versions");
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            if (versionId.equals(getString(v, "id"))) {
                return v;
            }
        }
        return null;
    }

    private boolean matchesCategory(JsonObject version, VersionCategory category) {
        VersionCategory selected = category == null ? VersionCategory.FEATURED : category;
        String type = getString(version, "type");
        return switch (selected) {
            case FEATURED -> "release".equals(type) || "snapshot".equals(type) || isAprilFoolsVersion(version);
            case RELEASE -> "release".equals(type);
            case PREVIEW -> "snapshot".equals(type);
            case APRIL_FOOLS -> isAprilFoolsVersion(version);
            case ALL -> true;
        };
    }

    private boolean isAprilFoolsVersion(JsonObject version) {
        String id = getString(version, "id");
        if (APRIL_FOOLS_VERSION_IDS.contains(id)) {
            return true;
        }

        String lowerId = id.toLowerCase();
        if (lowerId.contains("shareware")
                || lowerId.contains("infinite")
                || lowerId.contains("oneblockatatime")
                || lowerId.contains("_or_b")
                || lowerId.contains("potato")
                || lowerId.contains("craftmine")
                || lowerId.contains("rv-pre")) {
            return true;
        }

        String releaseTime = getString(version, "releaseTime");
        return "snapshot".equals(getString(version, "type"))
                && (releaseTime.startsWith("20") || releaseTime.startsWith("19"))
                && releaseTime.length() >= 10
                && "-04-01".equals(releaseTime.substring(4, 10));
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }
}
