package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.RuleEvaluator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Locale;

/** One-time completeness check used to migrate installations created before completion markers. */
public final class LegacyGameInstallationVerifier {
    private LegacyGameInstallationVerifier() {
    }

    public static boolean isComplete(String versionId) {
        try {
            File versionJsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
            File clientJar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), versionId);
            if (!versionJsonFile.isFile() || !clientJar.isFile()) {
                return false;
            }
            JsonObject versionJson = HttpUtil.readJson(versionJsonFile);
            if (!clientMatches(versionJson, clientJar)
                    || !librariesComplete(versionJson)
                    || !assetsComplete(versionJson)) {
                return false;
            }
            return true;
        } catch (IOException | RuntimeException invalidOrIncomplete) {
            return false;
        }
    }

    private static boolean clientMatches(JsonObject versionJson, File clientJar) {
        JsonObject downloads = object(versionJson, "downloads");
        JsonObject client = object(downloads, "client");
        return client != null && matchesDeclaredFile(clientJar, client);
    }

    private static boolean librariesComplete(JsonObject versionJson) throws IOException {
        JsonArray libraries = versionJson.has("libraries") && versionJson.get("libraries").isJsonArray()
                ? versionJson.getAsJsonArray("libraries") : new JsonArray();
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String bits = architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
        String nativeClassifier = "windows-" + FileUtil.nativeArchitecture(architecture);
        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) return false;
            JsonObject library = element.getAsJsonObject();
            if (library.has("rules") && !RuleEvaluator.isAllowed(library.getAsJsonArray("rules"))) {
                continue;
            }
            JsonObject downloads = object(library, "downloads");
            if (downloads != null) {
                JsonObject artifact = object(downloads, "artifact");
                if (artifact != null && !managedFileMatches(ECLConfig.getLibrariesDir(), artifact)) {
                    return false;
                }
                JsonObject classifiers = object(downloads, "classifiers");
                if (classifiers != null) {
                    String nativeKey = GameDownloader.nativeClassifierKey(
                            library, classifiers, "windows", bits, nativeClassifier);
                    if (nativeKey != null
                            && !managedFileMatches(ECLConfig.getLibrariesDir(),
                                    classifiers.getAsJsonObject(nativeKey))) {
                        return false;
                    }
                }
            } else {
                String coordinate = JsonUtil.getString(library, "name", "");
                String repository = JsonUtil.getString(library, "url", "");
                if (MavenCoordinates.isSimpleCoordinate(coordinate) && !repository.isBlank()) {
                    File target = FileUtil.safeResolveUnder(
                            ECLConfig.getLibrariesDir(), MavenCoordinates.repositoryPath(coordinate));
                    if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean assetsComplete(JsonObject versionJson) throws IOException {
        JsonObject assetIndex = object(versionJson, "assetIndex");
        if (assetIndex == null) return true;
        String assetId = JsonUtil.getString(assetIndex, "id", "");
        FileUtil.requireSafeVersionId(assetId);
        File indexFile = FileUtil.safeResolveUnder(
                ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");
        if (!matchesDeclaredFile(indexFile, assetIndex)) return false;

        JsonObject indexJson = HttpUtil.readJson(indexFile);
        JsonObject objects = object(indexJson, "objects");
        if (objects == null) return false;
        File objectRoot = new File(ECLConfig.getAssetsDir(), "objects");
        for (String name : objects.keySet()) {
            JsonObject asset = object(objects, name);
            if (asset == null) return false;
            String hash = JsonUtil.getString(asset, "hash", "");
            long size = JsonUtil.getLong(asset, "size", -1L);
            if (!hash.matches("(?i)[0-9a-f]{40}") || size <= 0) return false;
            File target = FileUtil.safeResolveUnder(
                    objectRoot, hash.substring(0, 2) + "/" + hash);
            if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || target.length() != size) {
                return false;
            }
        }
        return true;
    }

    private static boolean managedFileMatches(File root, JsonObject metadata) throws IOException {
        String path = JsonUtil.getString(metadata, "path", "");
        if (path.isBlank()) return false;
        return matchesDeclaredFile(FileUtil.safeResolveUnder(root, path), metadata);
    }

    private static boolean matchesDeclaredFile(File file, JsonObject metadata) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        long size = JsonUtil.getLong(metadata, "size", -1L);
        if (size > 0 && file.length() != size) return false;
        String sha1 = JsonUtil.getString(metadata, "sha1", "");
        return sha1.matches("(?i)[0-9a-f]{40}") && FileUtil.verifySha1(file, sha1);
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }
}
