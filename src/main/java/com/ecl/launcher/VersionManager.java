package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.Messages;
import com.ecl.util.MinecraftRuleUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile Map<String, JsonObject> versionIndex = Map.of();
    private final Map<String, String> displayNameCache = new ConcurrentHashMap<>();

    public enum VersionCategory {
        FEATURED("version.featured"),
        RELEASE("version.release"),
        PREVIEW("version.preview"),
        APRIL_FOOLS("version.aprilFools"),
        ALL("version.all");

        private final String labelKey;

        VersionCategory(String labelKey) {
            this.labelKey = labelKey;
        }

        public String getLabel() {
            return Messages.get(labelKey);
        }

        @Override
        public String toString() {
            return Messages.get(labelKey);
        }
    }

    public void refresh() throws IOException {
        File cache = new File(ECLConfig.getVersionsDir(), "version_manifest.json");
        try {
            manifest = HttpUtil.getJsonWithMirrors(ECLConfig.MC_VERSION_MANIFEST_URL, null);
            HttpUtil.writeJson(cache, manifest);
            versionIndex = buildVersionIndex(manifest);
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

    public synchronized List<String> mergeLocalLoaderProfiles(List<String> remoteVersions) {
        displayNameCache.clear();
        List<String> base = remoteVersions == null ? List.of() : List.copyOf(remoteVersions);
        List<LocalVersionProfile> localProfiles = getLocalVersionProfiles();
        Map<String, List<LocalVersionProfile>> byMinecraftVersion = new LinkedHashMap<>();
        Map<String, LocalVersionProfile> profilesById = new HashMap<>();
        for (LocalVersionProfile profile : localProfiles) {
            byMinecraftVersion.computeIfAbsent(profile.minecraftVersion(), ignored -> new ArrayList<>())
                    .add(profile);
            profilesById.put(profile.profileId(), profile);
        }
        byMinecraftVersion.values().forEach(profiles ->
                profiles.sort(Comparator.comparing(LocalVersionProfile::loader)
                        .thenComparing(LocalVersionProfile::profileId)));

        List<String> result = new ArrayList<>();
        Set<String> added = new java.util.HashSet<>();
        Set<String> remoteVersionIds = new java.util.HashSet<>(base);
        for (String version : base) {
            if (added.add(version)) {
                result.add(version);
            }
            for (LocalVersionProfile profile : byMinecraftVersion.getOrDefault(version, List.of())) {
                if (added.add(profile.profileId())) {
                    result.add(profile.profileId());
                }
            }
        }

        List<String> unmatchedMinecraftVersions = byMinecraftVersion.keySet().stream()
                .filter(version -> !remoteVersionIds.contains(version))
                .sorted((left, right) -> compareMinecraftVersionIds(right, left))
                .toList();
        for (String minecraftVersion : unmatchedMinecraftVersions) {
            int insertionIndex = findProfileInsertionIndex(
                    result, minecraftVersion, profilesById);
            for (LocalVersionProfile profile : byMinecraftVersion.get(minecraftVersion)) {
                if (added.add(profile.profileId())) {
                    result.add(insertionIndex++, profile.profileId());
                }
            }
        }
        return result;
    }

    private static int findProfileInsertionIndex(
            List<String> entries,
            String minecraftVersion,
            Map<String, LocalVersionProfile> profilesById
    ) {
        int[] requested = parseReleaseVersion(minecraftVersion);
        if (requested == null) {
            return entries.size();
        }
        for (int index = 0; index < entries.size(); index++) {
            String entry = entries.get(index);
            LocalVersionProfile profile = profilesById.get(entry);
            String entryVersion = profile == null ? entry : profile.minecraftVersion();
            int[] candidate = parseReleaseVersion(entryVersion);
            if (candidate != null && compareReleaseParts(requested, candidate) > 0) {
                return index;
            }
        }
        return entries.size();
    }

    private static int compareMinecraftVersionIds(String left, String right) {
        int[] leftParts = parseReleaseVersion(left);
        int[] rightParts = parseReleaseVersion(right);
        if (leftParts != null && rightParts != null) {
            return compareReleaseParts(leftParts, rightParts);
        }
        if (leftParts != null) {
            return 1;
        }
        if (rightParts != null) {
            return -1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static int[] parseReleaseVersion(String version) {
        if (version == null || !version.matches("\\d+(?:\\.\\d+)*")) {
            return null;
        }
        String[] segments = version.split("\\.");
        int[] result = new int[segments.length];
        try {
            for (int index = 0; index < segments.length; index++) {
                result[index] = Integer.parseInt(segments[index]);
            }
            return result;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int compareReleaseParts(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int leftPart = index < left.length ? left[index] : 0;
            int rightPart = index < right.length ? right[index] : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    public List<LocalVersionProfile> getLocalVersionProfiles() {
        File versionsDirectory = ECLConfig.getVersionsDir();
        File[] directories = versionsDirectory.listFiles(File::isDirectory);
        if (directories == null) {
            return List.of();
        }
        List<LocalVersionProfile> profiles = new ArrayList<>();
        for (File directory : directories) {
            String profileId = directory.getName();
            File jsonFile = new File(directory, profileId + ".json");
            if (!jsonFile.isFile()) {
                continue;
            }
            try {
                JsonObject json = HttpUtil.readJson(jsonFile);
                String loader = JsonUtil.getString(json, "eclModLoader", "").toLowerCase(Locale.ROOT);
                if (loader.isBlank()) {
                    loader = detectLoader(json);
                }
                if (loader.isBlank()) {
                    continue;
                }
                String minecraftVersion = JsonUtil.getString(json, "eclMinecraftVersion", "");
                if (minecraftVersion.isBlank()) {
                    minecraftVersion = JsonUtil.getString(json, "inheritsFrom", "");
                }
                if (minecraftVersion.isBlank()) {
                    continue;
                }
                profiles.add(new LocalVersionProfile(profileId, minecraftVersion, loader));
            } catch (IOException e) {
                LOGGER.warn("Failed to inspect local version profile {}", profileId, e);
            }
        }
        profiles.sort(Comparator.comparing(LocalVersionProfile::minecraftVersion).reversed()
                .thenComparing(LocalVersionProfile::loader)
                .thenComparing(LocalVersionProfile::profileId));
        return List.copyOf(profiles);
    }

    public synchronized String getVersionDisplayName(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        return displayNameCache.computeIfAbsent(versionId, id -> {
            for (LocalVersionProfile profile : getLocalVersionProfiles()) {
                if (profile.profileId().equals(id)) {
                    return profile.minecraftVersion() + " · " + loaderDisplayName(profile.loader())
                            + "  [" + profile.profileId() + "]";
                }
            }
            return id + " · 原版";
        });
    }

    public String getVersionUrl(String versionId) {
        ensureManifestLoaded();
        JsonObject version = findVersion(versionId);
        return version == null ? null : JsonUtil.getString(version, "url", "");
    }

    public VersionDownloadTarget resolveDownloadTarget(String profileId) throws IOException {
        if (profileId == null || profileId.isBlank()) {
            throw new IOException("Version profile id is blank");
        }
        ensureManifestLoaded();
        JsonObject localProfile = loadVersionJson(profileId);
        if (localProfile == null && findVersion(profileId) == null) {
            throw new IOException("Unknown version profile: " + profileId);
        }
        String downloadVersionId = resolveDownloadVersionId(
                profileId, new java.util.HashSet<>());
        JsonObject manifestVersion = findVersion(downloadVersionId);
        String url = manifestVersion == null
                ? "" : JsonUtil.getString(manifestVersion, "url", "");
        return new VersionDownloadTarget(profileId, downloadVersionId, url);
    }

    public String resolveMinecraftVersionId(String profileId) throws IOException {
        if (profileId == null || profileId.isBlank()) {
            throw new IOException("Version profile id is blank");
        }
        ensureManifestLoaded();
        return resolveMinecraftVersionId(profileId, new java.util.HashSet<>());
    }

    private String resolveMinecraftVersionId(
            String profileId,
            Set<String> visited
    ) throws IOException {
        if (!visited.add(profileId)) {
            throw new IOException("Circular version inheritance while resolving Minecraft version: "
                    + profileId);
        }
        JsonObject json = loadVersionJson(profileId);
        if (json == null) {
            if (findVersion(profileId) != null || visited.size() > 1) {
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

    private String resolveDownloadVersionId(
            String versionId,
            Set<String> visited
    ) throws IOException {
        if (!visited.add(versionId)) {
            throw new IOException("Circular version inheritance while resolving download target: "
                    + versionId);
        }
        JsonObject json = loadVersionJson(versionId);
        if (json == null) {
            return versionId;
        }
        String explicitJar = JsonUtil.getString(json, "jar", "");
        if (!explicitJar.isBlank()) {
            return explicitJar;
        }
        String parent = JsonUtil.getString(json, "inheritsFrom", "");
        if (!parent.isBlank()) {
            return resolveDownloadVersionId(parent, visited);
        }
        if (hasClientDownload(json)) {
            return versionId;
        }
        String minecraftVersion = JsonUtil.getString(json, "eclMinecraftVersion", "");
        return minecraftVersion.isBlank() || minecraftVersion.equals(versionId)
                ? versionId
                : resolveDownloadVersionId(minecraftVersion, visited);
    }

    private static boolean hasClientDownload(JsonObject json) {
        JsonObject downloads = json == null ? null : json.getAsJsonObject("downloads");
        return downloads != null && downloads.has("client")
                && downloads.get("client").isJsonObject();
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
            JsonObject cached = HttpUtil.readJson(cache);
            versionIndex = buildVersionIndex(cached);
            return cached;
        } catch (IOException e) {
            LOGGER.warn("Failed to read cached version manifest from {}", cache, e);
            return null;
        }
    }

    private static Map<String, JsonObject> buildVersionIndex(JsonObject manifest) {
        if (manifest == null || !manifest.has("versions")) {
            return Map.of();
        }
        JsonArray arr = manifest.getAsJsonArray("versions");
        Map<String, JsonObject> index = new HashMap<>(arr.size() * 2);
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject v = el.getAsJsonObject();
            String id = JsonUtil.getString(v, "id", "");
            if (!id.isEmpty()) {
                index.putIfAbsent(id, v);
            }
        }
        return index;
    }

    private JsonObject findVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return null;
        }
        return versionIndex.get(versionId);
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

    private String getString(JsonObject object, String key) {
        return JsonUtil.getString(object, key, "");
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
                && "04-01".equals(releaseTime.substring(5, 10))) {
            return true;
        }

        String id = JsonUtil.getString(version, "id", "");
        return APRIL_FOOLS_VERSION_IDS.contains(id);
    }

    private static String detectLoader(JsonObject json) {
        String mainClass = JsonUtil.getString(json, "mainClass", "").toLowerCase(Locale.ROOT);
        if (mainClass.contains("fabricmc")) return "fabric";
        if (mainClass.contains("quiltmc")) return "quilt";
        if (mainClass.contains("neoforge")) return "neoforge";
        if (mainClass.contains("forge")) return "forge";
        JsonArray libraries = json.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement element : libraries) {
                if (!element.isJsonObject()) continue;
                String coordinate = JsonUtil.getString(element.getAsJsonObject(), "name", "")
                        .toLowerCase(Locale.ROOT);
                if (coordinate.contains("fabric-loader")) return "fabric";
                if (coordinate.contains("quilt-loader")) return "quilt";
                if (coordinate.contains("neoforge")) return "neoforge";
                if (coordinate.contains("minecraftforge") || coordinate.contains("forge:forge")) return "forge";
            }
        }
        return "";
    }

    private static String loaderDisplayName(String loader) {
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> loader;
        };
    }

    public record LocalVersionProfile(String profileId, String minecraftVersion, String loader) {
    }

    public record VersionDownloadTarget(
            String requestedProfileId,
            String downloadVersionId,
            String versionUrl
    ) {
    }

}
