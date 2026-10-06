package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.download.LegacyGameInstallationVerifier;
import com.ecl.util.FileLockLease;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.Messages;
import com.ecl.util.ManagedLockPaths;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class VersionManager {
    private volatile JsonObject manifest;
    private volatile Map<String, JsonObject> versionIndex = Map.of();
    private final VersionManifestStore manifestStore = new VersionManifestStore();
    private final VersionProfileResolver profileResolver = new VersionProfileResolver(this);
    private final VersionDownloadTargetResolver downloadTargetResolver =
            new VersionDownloadTargetResolver(this);
    private final Map<String, String> displayNameCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> legacyMigrationChecks =
            new ConcurrentHashMap<>();
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

    public synchronized List<LocalVersionProfile> getLocalVersionProfiles() {
        List<LocalVersionProfile> cached = localProfilesCache;
        if (cached != null) {
            return cached;
        }
        List<LocalVersionProfile> result = new LocalVersionProfileScanner().scan();
        localProfilesCache = result;
        return result;
    }

    /** Drop cached local profile scans and display names after install/delete/rename of versions. */
    public synchronized void invalidateLocalVersionProfiles() {
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
        String sha1 = manifestVersion == null
                ? "" : JsonUtil.getString(manifestVersion, "sha1", "");
        return new VersionDownloadTarget(profileId, downloadVersionId, url, sha1);
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
        try {
            DownloadState state = downloadState(versionId);
            if (state == null || !state.jar().isFile()) return false;
            if (state.marker().isFile()) return true;
            CompletableFuture<Boolean> migration = legacyMigrationChecks.get(state.jarVersion());
            return migration != null && migration.isDone() && !migration.isCompletedExceptionally()
                    && Boolean.TRUE.equals(migration.getNow(false));
        } catch (IOException e) {
            return false;
        }
    }

    public CompletableFuture<Boolean> ensureVersionDownloadedAsync(String versionId) {
        DownloadState state;
        try {
            state = downloadState(versionId);
        } catch (IOException invalidVersion) {
            return CompletableFuture.completedFuture(false);
        }
        if (state == null || !state.jar().isFile()) {
            return CompletableFuture.completedFuture(false);
        }
        if (state.marker().isFile()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> migration = legacyMigrationChecks.computeIfAbsent(
                state.jarVersion(), ignored -> CompletableFuture.supplyAsync(
                        () -> migrateLegacyInstallation(state)));
        migration.whenComplete((ready, error) -> {
            if (error != null) {
                legacyMigrationChecks.remove(state.jarVersion(), migration);
            }
        });
        return migration;
    }

    private boolean migrateLegacyInstallation(DownloadState state) {
        try (FileLockLease versionLock = FileLockLease.tryAcquire(
                ManagedLockPaths.versionDownload(
                        ECLConfig.getVersionsDir().toPath(), state.jarVersion()))) {
            if (versionLock == null) {
                throw new IllegalStateException(
                        "Version download or migration is already in progress: "
                                + state.jarVersion());
            }
            if (state.marker().isFile()) return true;
            if (!LegacyGameInstallationVerifier.isComplete(state.jarVersion())) return false;
            try {
                Files.writeString(state.marker().toPath(), "legacy-verified",
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException readOnlyInstallation) {
                // Keep the positive result cached for this process. Another process will perform
                // the same bounded migration check if the installation remains read-only.
            }
            return true;
        } catch (IOException invalidOrBusy) {
            return false;
        }
    }

    private DownloadState downloadState(String versionId) throws IOException {
        File json = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
        if (!json.isFile()) return null;
        String jarVersion = profileResolver.resolveClientJarVersion(
                versionId, new java.util.HashSet<>());
        File jar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), jarVersion);
        File marker = new File(FileUtil.safeVersionDirectory(
                ECLConfig.getVersionsDir(), jarVersion),
                ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER);
        return new DownloadState(jarVersion, jar, marker);
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
            String versionUrl,
            String versionSha1
    ) {
        public VersionDownloadTarget {
            versionUrl = versionUrl == null ? "" : versionUrl;
            versionSha1 = versionSha1 == null ? "" : versionSha1.trim();
        }
    }

    private record DownloadState(String jarVersion, File jar, File marker) {
    }

}
