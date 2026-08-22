package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.Messages;
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
import java.util.concurrent.ConcurrentHashMap;

public class VersionManager {
    private volatile JsonObject manifest;
    private volatile Map<String, JsonObject> versionIndex = Map.of();
    private final VersionManifestStore manifestStore = new VersionManifestStore();
    private final VersionProfileResolver profileResolver = new VersionProfileResolver(this);
    private final VersionDownloadTargetResolver downloadTargetResolver =
            new VersionDownloadTargetResolver(this);
    private final Map<String, String> displayNameCache = new ConcurrentHashMap<>();
    /** Cached scan of local version profiles; null means not computed yet. */
    private volatile List<LocalVersionProfile> localProfilesCache;

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
        manifest = manifestStore.refresh();
        versionIndex = VersionCatalogService.buildIndex(manifest);
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
        return VersionCatalogService.versions(manifest, category);
    }

    public List<String> getAllVersions() {
        return getVersions(VersionCategory.ALL);
    }

    public synchronized List<String> mergeLocalLoaderProfiles(List<String> remoteVersions) {
        displayNameCache.clear();
        localProfilesCache = null; // 版本列表刷新时强制重新扫描本地档案
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
        List<LocalVersionProfile> cached = localProfilesCache;
        if (cached != null) {
            return cached;
        }
        List<LocalVersionProfile> result = new LocalVersionProfileScanner().scan();
        localProfilesCache = result;
        return result;
    }

    /** Drop cached local profile scans and display names after install/delete/rename of versions. */
    public void invalidateLocalVersionProfiles() {
        displayNameCache.clear();
        localProfilesCache = null;
    }

    public synchronized String getVersionDisplayName(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        return displayNameCache.computeIfAbsent(versionId, id -> {
            for (LocalVersionProfile profile : getLocalVersionProfiles()) {
                if (profile.profileId().equals(id)) {
                    return profile.minecraftVersion() + " · " + LocalVersionProfileScanner.displayName(profile.loader())
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
        String downloadVersionId = downloadTargetResolver.resolve(
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
        return profileResolver.resolveMinecraftVersionId(profileId, new java.util.HashSet<>());
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
        File json;
        try {
            json = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
        } catch (IOException e) {
            return false;
        }
        if (!json.isFile()) {
            return false;
        }
        try {
            String jarVersion = profileResolver.resolveClientJarVersion(versionId, new java.util.HashSet<>());
            File jar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), jarVersion);
            return jar.isFile();
        } catch (IOException e) {
            return false;
        }
    }

    public JsonObject loadVersionJson(String versionId) throws IOException {
        File json = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
        if (json.exists()) {
            return HttpUtil.readJson(json);
        }
        return null;
    }

    private synchronized void ensureManifestLoaded() {
        if (manifest == null) {
            manifest = manifestStore.loadCached();
            versionIndex = VersionCatalogService.buildIndex(manifest);
        }
    }

    JsonObject findVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return null;
        }
        return versionIndex.get(versionId);
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
