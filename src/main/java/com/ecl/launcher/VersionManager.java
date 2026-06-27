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

public class VersionManager {
    private JsonObject manifest;

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
        ensureManifestLoaded();
        List<String> versions = new ArrayList<>();
        if (manifest == null) {
            return versions;
        }

        JsonArray arr = manifest.getAsJsonArray("versions");
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            if ("release".equals(v.get("type").getAsString())) {
                versions.add(v.get("id").getAsString());
            }
        }
        return versions;
    }

    public List<String> getAllVersions() {
        ensureManifestLoaded();
        List<String> versions = new ArrayList<>();
        if (manifest == null) {
            return versions;
        }

        JsonArray arr = manifest.getAsJsonArray("versions");
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            versions.add(v.get("id").getAsString());
        }
        return versions;
    }

    public String getVersionUrl(String versionId) {
        ensureManifestLoaded();
        if (manifest == null) {
            return null;
        }

        JsonArray arr = manifest.getAsJsonArray("versions");
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            if (versionId.equals(v.get("id").getAsString())) {
                return v.get("url").getAsString();
            }
        }
        return null;
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
}
