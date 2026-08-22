package com.ecl.launcher;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Set;

/** Resolves the base Minecraft version whose client metadata should be downloaded. */
final class VersionDownloadTargetResolver {
    private final VersionManager versionManager;

    VersionDownloadTargetResolver(VersionManager versionManager) {
        this.versionManager = versionManager;
    }

    String resolve(String versionId, Set<String> visited) throws IOException {
        if (!visited.add(versionId)) {
            throw new IOException("Circular version inheritance while resolving download target: "
                    + versionId);
        }
        JsonObject json = versionManager.loadVersionJson(versionId);
        if (json == null) {
            return versionId;
        }
        String explicitJar = JsonUtil.getString(json, "jar", "");
        if (!explicitJar.isBlank()) {
            return explicitJar;
        }
        String parent = JsonUtil.getString(json, "inheritsFrom", "");
        if (!parent.isBlank()) {
            return resolve(parent, visited);
        }
        if (VersionProfileResolver.hasClientDownload(json)) {
            return versionId;
        }
        String minecraftVersion = JsonUtil.getString(json, "eclMinecraftVersion", "");
        return minecraftVersion.isBlank() || minecraftVersion.equals(versionId)
                ? versionId
                : resolve(minecraftVersion, visited);
    }
}
