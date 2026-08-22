package com.ecl.launcher;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Set;

/** Resolves local version inheritance without owning manifest or filesystem storage. */
final class VersionProfileResolver {
    private final VersionManager versionManager;

    VersionProfileResolver(VersionManager versionManager) {
        this.versionManager = versionManager;
    }

    String resolveMinecraftVersionId(String profileId, Set<String> visited) throws IOException {
        if (!visited.add(profileId)) {
            throw new IOException("Circular version inheritance while resolving Minecraft version: "
                    + profileId);
        }
        JsonObject json = versionManager.loadVersionJson(profileId);
        if (json == null) {
            if (versionManager.findVersion(profileId) != null || visited.size() > 1) {
                return profileId;
            }
            throw new IOException("Missing inherited Minecraft version metadata: " + profileId);
        }
        String explicit = JsonUtil.getString(json, "eclMinecraftVersion", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        String parent = JsonUtil.getString(json, "inheritsFrom", "");
        return parent.isBlank()
                ? profileId
                : resolveMinecraftVersionId(parent, visited);
    }

    String resolveClientJarVersion(String versionId, Set<String> visited) throws IOException {
        if (!visited.add(versionId)) {
            throw new IOException("Circular version inheritance while resolving client JAR: " + versionId);
        }
        JsonObject json = versionManager.loadVersionJson(versionId);
        if (json == null) {
            throw new IOException("Missing version JSON: " + versionId);
        }
        String explicitJar = JsonUtil.getString(json, "jar", "");
        if (!explicitJar.isBlank()) {
            return explicitJar;
        }
        String parent = JsonUtil.getString(json, "inheritsFrom", "");
        if (!parent.isBlank()) {
            return resolveClientJarVersion(parent, visited);
        }
        if (hasClientDownload(json)) {
            return versionId;
        }
        String minecraftVersion = JsonUtil.getString(json, "eclMinecraftVersion", "");
        return minecraftVersion.isBlank() || minecraftVersion.equals(versionId)
                ? versionId
                : resolveClientJarVersion(minecraftVersion, visited);
    }

    static boolean hasClientDownload(JsonObject json) {
        JsonObject downloads = json == null ? null : json.getAsJsonObject("downloads");
        return downloads != null && downloads.has("client")
                && downloads.get("client").isJsonObject();
    }
}
