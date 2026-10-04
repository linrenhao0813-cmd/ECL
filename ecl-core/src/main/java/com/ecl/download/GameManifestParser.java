package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.RuleEvaluator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Parses version JSON, library metadata, and inherited-client state. */
final class GameManifestParser {
    private static final long MAX_GAME_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024;

    private GameManifestParser() {
    }

    static boolean hasUsableInheritedClient(JsonObject versionJson) {
        if (!versionJson.has("inheritsFrom")) return false;
        String current = versionJson.get("inheritsFrom").getAsString();
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            File jar;
            File jsonFile;
            try {
                jar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), current);
                jsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), current);
            } catch (IOException e) {
                return false;
            }
            if (jar.isFile()) return true;
            if (!jsonFile.isFile()) return false;
            try {
                JsonObject parentJson = HttpUtil.readJson(jsonFile);
                if (parentJson.has("jar")) {
                    String jarId = parentJson.get("jar").getAsString();
                    return FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), jarId).isFile();
                }
                current = parentJson.has("inheritsFrom")
                        ? parentJson.get("inheritsFrom").getAsString() : null;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    static List<String> getMissingLibraries(JsonObject versionJson) {
        List<String> missing = new ArrayList<>();
        if (versionJson == null || !versionJson.has("libraries")) return missing;
        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("rules") && !RuleEvaluator.isAllowed(lib.getAsJsonArray("rules"))) {
                continue;
            }
            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    String path = artifact.get("path").getAsString();
                    File target = safeLibraryTarget(path);
                    if (target != null && !target.exists()) {
                        missing.add(lib.has("name") ? lib.get("name").getAsString() : path);
                    }
                }
            } else {
                String name = lib.has("name") ? lib.get("name").getAsString() : "";
                String repository = lib.has("url") ? lib.get("url").getAsString() : "";
                if (MavenCoordinates.isSimpleCoordinate(name) && !repository.isBlank()) {
                    File target = safeLibraryTarget(MavenCoordinates.repositoryPath(name));
                    if (target != null && !target.exists()) {
                        missing.add(name);
                    }
                }
            }
        }
        return missing;
    }

    static String requiredString(JsonObject object, String key, String description)
            throws IOException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            throw new IOException("Missing or invalid " + description);
        }
        try {
            String value = object.get(key).getAsString();
            if (value == null || value.isBlank()) {
                throw new IOException("Missing or blank " + description);
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid " + description, invalid);
        }
    }

    static long requiredPositiveSize(JsonObject object, String key, String description)
            throws IOException {
        try {
            long value = object != null && object.has(key) ? object.get(key).getAsLong() : -1L;
            if (value <= 0 || value > MAX_GAME_ARTIFACT_BYTES) {
                throw new IOException("Missing or invalid " + description + " size");
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid " + description + " size", invalid);
        }
    }

    private static File safeLibraryTarget(String path) {
        try {
            return FileUtil.safeResolveUnder(ECLConfig.getLibrariesDir(), path);
        } catch (IOException e) {
            return null;
        }
    }
}
