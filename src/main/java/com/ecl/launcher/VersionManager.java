package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VersionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionManager.class);
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

    private volatile JsonObject manifest;

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
                versions.add(JsonUtil.getString(v, "id", ""));
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
        return version == null ? null : JsonUtil.getString(version, "url", "");
    }

    public String getVersionType(String versionId) {
        ensureManifestLoaded();
        JsonObject version = findVersion(versionId);
        return version == null ? "" : JsonUtil.getString(version, "type", "");
    }

    public boolean isReleaseOrSnapshot(String versionId) {
        String type = getVersionType(versionId);
        return "release".equals(type) || "snapshot".equals(type);
    }

    public boolean isVersionDownloaded(String versionId) {
        File json = new File(ECLConfig.getVersionsDir(), versionId + "/" + versionId + ".json");
        if (!json.isFile()) {
            return false;
        }
        try {
            String jarVersion = resolveClientJarVersion(versionId, new java.util.HashSet<>());
            File jar = new File(ECLConfig.getVersionsDir(), jarVersion + "/" + jarVersion + ".jar");
            return jar.isFile();
        } catch (IOException e) {
            return false;
        }
    }

    private String resolveClientJarVersion(String versionId, java.util.Set<String> visited) throws IOException {
        if (!visited.add(versionId)) {
            throw new IOException("Circular version inheritance while resolving client JAR: " + versionId);
        }
        JsonObject json = loadVersionJson(versionId);
        if (json == null) {
            throw new IOException("Missing version JSON: " + versionId);
        }
        String explicitJar = JsonUtil.getString(json, "jar", "");
        if (!explicitJar.isBlank()) {
            return explicitJar;
        }
        String parent = JsonUtil.getString(json, "inheritsFrom", "");
        return parent.isBlank() ? versionId : resolveClientJarVersion(parent, visited);
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
        } catch (IOException e) {
            LOGGER.warn("Failed to read cached version manifest from {}", cache, e);
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
            if (versionId.equals(JsonUtil.getString(v, "id", ""))) {
                return v;
            }
        }
        return null;
    }

    private boolean isClientJarValid(JsonObject versionJson, File jar) {
        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("client")) {
            return jar.exists();
        }

        JsonObject client = downloads.getAsJsonObject("client");
        String sha1 = getString(client, "sha1");
        return sha1.isBlank() ? jar.exists() : FileUtil.verifySha1(jar, sha1);
    }

    private boolean areLibrariesReady(JsonObject versionJson) {
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries == null) {
            return true;
        }

        String nativeClassifier = FileUtil.getNativeClassifier();
        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("rules") && !MinecraftRuleUtil.checkRules(lib.getAsJsonArray("rules"))) {
                continue;
            }
            if (!lib.has("downloads")) {
                continue;
            }

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads.has("artifact") && !isArtifactReady(downloads.getAsJsonObject("artifact"))) {
                return false;
            }
            if (downloads.has("classifiers") && !isNativeArtifactReady(downloads.getAsJsonObject("classifiers"), nativeClassifier)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNativeArtifactReady(JsonObject classifiers, String nativeClassifier) {
        for (String key : MinecraftRuleUtil.nativeKeys(nativeClassifier)) {
            if (classifiers.has(key)) {
                return isArtifactReady(classifiers.getAsJsonObject(key));
            }
        }
        return true;
    }

    private boolean isArtifactReady(JsonObject artifact) {
        String path = getString(artifact, "path");
        if (path.isBlank()) {
            return true;
        }

        File file = new File(ECLConfig.getLibrariesDir(), path);
        String sha1 = getString(artifact, "sha1");
        return sha1.isBlank() ? file.exists() : FileUtil.verifySha1(file, sha1);
    }

    private boolean isAssetIndexReady(JsonObject versionJson) {
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        if (assetIndex == null) {
            return true;
        }

        String assetId = getString(assetIndex, "id");
        if (assetId.isBlank()) {
            return true;
        }

        File indexFile = new File(ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");
        String sha1 = getString(assetIndex, "sha1");
        return sha1.isBlank() ? indexFile.exists() : FileUtil.verifySha1(indexFile, sha1);
    }

    private boolean matchesCategory(JsonObject version, VersionCategory category) {
        VersionCategory selected = category == null ? VersionCategory.FEATURED : category;
        String type = JsonUtil.getString(version, "type", "");
        return switch (selected) {
            case FEATURED -> "release".equals(type) || "snapshot".equals(type) || isAprilFoolsVersion(version);
            case RELEASE -> "release".equals(type);
            case PREVIEW -> "snapshot".equals(type);
            case APRIL_FOOLS -> isAprilFoolsVersion(version);
            case ALL -> true;
        };
    }

    private boolean isAprilFoolsVersion(JsonObject version) {
        String releaseTime = JsonUtil.getString(version, "releaseTime", "");
        if ("snapshot".equals(JsonUtil.getString(version, "type", ""))
                && releaseTime.length() >= 10
                && "-04-01".equals(releaseTime.substring(4, 10))) {
            return true;
        }

        String id = JsonUtil.getString(version, "id", "");
        return APRIL_FOOLS_VERSION_IDS.contains(id);
    }

}
