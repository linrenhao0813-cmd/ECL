package com.ecl.download;

import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.launch.GameProcessMarker;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.JsonUtil;
import com.ecl.util.NetworkUriPolicy;
import com.ecl.util.Messages;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Checks one instance and repairs only authenticated Minecraft and loader dependency files. */
public final class GameInstallationRepairService {
    /**
     * Upper bound for one base-metadata repair download. The downloader's own listeners report
     * progress; this guard only stops a stalled transfer from blocking the repair task forever.
     */
    private static final int REPAIR_DOWNLOAD_TIMEOUT_SECONDS = 600;
    private final InstanceOperationCoordinator operations;
    private final Predicate<UUID> instanceRunning;

    public GameInstallationRepairService(InstanceOperationCoordinator operations,
                                         Predicate<UUID> instanceRunning) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
    }

    public GameInstallationVerifier.Report inspect(String profileId, Path versions,
                                                     Path libraries, Path assets) {
        return new GameInstallationVerifier(versions, libraries, assets).inspect(profileId);
    }

    public Result repair(ModInstanceContext instance, GameInstallationVerifier.Report expected,
                         VersionManager.VersionDownloadTarget target, GameDownloader downloader,
                         GameDownloader.DownloadListener listener, BooleanSupplier cancelled,
                         Path versions, Path libraries, Path assets, Path gameRoot) throws Exception {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(downloader, "downloader");
        if (!instance.profileId().equals(expected.profileId())
                || !instance.profileId().equals(target.requestedProfileId())) {
            throw new IOException("Repair plan belongs to a different instance");
        }
        if (expected.issues().stream().noneMatch(check -> isRepairable(check, target)
                && check.status() != GameInstallationVerifier.Status.VERIFIED)) {
            throw new IOException(Messages.get("instanceRepair.noAutoFix"));
        }
        requireTarget(instance, target, versions);
        requireNoGameRunning(instance, gameRoot);
        try (AutoCloseable ignored = operations.acquireInterruptibly(instance.instanceId());
             InstanceOperationLease processLock = InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
            if (processLock == null) throw new IOException(Messages.get("instanceRepair.busy"));
            requireNoGameRunning(instance, gameRoot);
            GameInstallationVerifier.Report current = inspect(instance.profileId(), versions, libraries, assets);
            if (!current.fingerprint().equals(expected.fingerprint())) {
                throw new IOException(Messages.get("instanceRepair.stale"));
            }
            checkCancelled(cancelled);
            runBaseRepair(target, downloader, listener, cancelled);
            checkCancelled(cancelled);
            downloader.downloadLibrariesForVersion(instance.profileId(), listener);
            checkCancelled(cancelled);
            VersionMetadata metadata = new VersionRepository(versions.toFile()).resolve(instance.profileId());
            if (metadata.clientLogging() != null) metadata.clientLogging().ensureDownloaded(assets.toFile());
            LegacyAssetMaterializer.prepare(metadata.assetIndex(), assets.toFile(), instance.gameDirectory().toFile());
            checkCancelled(cancelled);
            GameInstallationVerifier.Report after = inspect(instance.profileId(), versions, libraries, assets);
            return new Result(expected, after);
        }
    }

    private static void runBaseRepair(VersionManager.VersionDownloadTarget target,
                                      GameDownloader downloader,
                                      GameDownloader.DownloadListener listener,
                                      BooleanSupplier cancelled) throws Exception {
        if (target.versionUrl().isBlank()) throw new IOException(Messages.get("instanceRepair.metadataUrlMissing"));
        if (!target.versionSha1().matches("(?i)[0-9a-f]{40}")) {
            throw new IOException(Messages.get("instanceRepair.metadataHashMissing"));
        }
        URI metadataUri = URI.create(target.versionUrl());
        NetworkUriPolicy.requireArtifactDownload(metadataUri, "Minecraft metadata URL");
        requireOfficialMetadataHost(metadataUri);
        AtomicReference<String> failure = new AtomicReference<>();
        downloader.setListener(new GameDownloader.DownloadListener() {
            @Override public void onStatus(String message) {
                if (listener != null) listener.onStatus(message);
            }

            @Override public void onProgress(long downloaded, long total) {
                if (listener != null) listener.onProgress(downloaded, total);
            }

            @Override public void onError(String message) {
                failure.set(message);
                if (listener != null) listener.onError(message);
            }

            @Override public void onComplete() {
                if (listener != null) listener.onComplete();
            }
        });
        Future<?> task = downloader.downloadVersionAsync(
                target.downloadVersionId(), target.versionUrl(), target.versionSha1());
        if (cancelled != null && cancelled.getAsBoolean()) downloader.cancelDownload();
        try {
            task.get(REPAIR_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IOException("Game repair download failed", cause);
        } catch (TimeoutException timedOut) {
            downloader.cancelDownload();
            throw new IOException(Messages.get("instanceRepair.downloadTimeout"), timedOut);
        } catch (InterruptedException interrupted) {
            downloader.cancelDownload();
            Thread.currentThread().interrupt();
            throw new CancellationException("Game repair cancelled");
        } catch (CancellationException cancelledDownload) {
            throw cancelledDownload;
        }
        checkCancelled(cancelled);
        if (failure.get() != null && !failure.get().isBlank()) {
            throw new IOException(failure.get());
        }
    }

    private static void requireTarget(ModInstanceContext instance,
                                      VersionManager.VersionDownloadTarget target,
                                      Path versions) throws IOException {
        FileUtil.requireSafeVersionId(target.downloadVersionId());
        String declaredBase = declaredClientId(instance.profileId(), versions);
        if (!target.downloadVersionId().equals(declaredBase)) {
            throw new IOException("Repair target no longer matches this instance's Minecraft version");
        }
        try {
            VersionMetadata profile = new VersionRepository(versions.toFile()).resolve(instance.profileId());
            if (!profile.clientJarId().equals(declaredBase)) {
                throw new IOException("Repair target no longer matches this instance's Minecraft version");
            }
        } catch (IOException missingOrDamagedMetadata) {
            // The authoritative target can repair a missing or damaged base JSON; custom profile
            // metadata is still required to resolve a different target id above.
        }
        URI metadataUri = URI.create(target.versionUrl());
        NetworkUriPolicy.requireArtifactDownload(metadataUri, "Minecraft metadata URL");
        requireOfficialMetadataHost(metadataUri);
    }

    private static String declaredClientId(String profileId, Path versions) throws IOException {
        Set<String> visited = new HashSet<>();
        String current = profileId;
        for (int depth = 0; depth < 64; depth++) {
            FileUtil.requireSafeVersionId(current);
            if (!visited.add(current)) throw new IOException("Circular version metadata inheritance");
            Path file = FileUtil.safeVersionJson(versions.toFile(), current).toPath();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return current;
            JsonObject json;
            try {
                json = HttpUtil.readJson(file.toFile());
            } catch (IOException damaged) {
                return current;
            }
            String jar = JsonUtil.getString(json, "jar", "");
            if (!jar.isBlank()) {
                FileUtil.requireSafeVersionId(jar);
                return jar;
            }
            String parent = JsonUtil.getString(json, "inheritsFrom", "");
            if (!parent.isBlank()) {
                current = parent;
                continue;
            }
            JsonObject downloads = json.has("downloads") && json.get("downloads").isJsonObject()
                    ? json.getAsJsonObject("downloads") : null;
            if (downloads != null && downloads.has("client")) return current;
            String minecraft = JsonUtil.getString(json, "eclMinecraftVersion", "");
            if (!minecraft.isBlank() && !minecraft.equals(current)) {
                current = minecraft;
                continue;
            }
            return current;
        }
        throw new IOException("Version metadata inheritance is too deep");
    }

    private static boolean isRepairable(GameInstallationVerifier.Check check,
                                       VersionManager.VersionDownloadTarget target) {
        if (check.repairable()) return true;
        return check.category() == GameInstallationVerifier.Category.METADATA
                && target != null && target.downloadVersionId() != null
                && !target.downloadVersionId().isBlank()
                && target.versionSha1().matches("(?i)[0-9a-f]{40}");
    }

    private static void requireOfficialMetadataHost(URI uri) throws IOException {
        String host = uri.getHost();
        if (NetworkUriPolicy.isLoopbackHostLiteral(host)) return;
        if (host == null || !host.toLowerCase(java.util.Locale.ROOT).endsWith(".mojang.com")) {
            throw new IOException("Repair metadata source is not an official Mojang host");
        }
    }

    private void requireNoGameRunning(ModInstanceContext instance, Path gameRoot)
            throws IOException {
        if (instanceRunning.test(instance.instanceId()) || GameProcessMarker.isRunning(instance.gameDirectory())) {
            throw new IOException(Messages.get("instanceRepair.instanceRunning"));
        }
        Path normalizedGameRoot = gameRoot.toAbsolutePath().normalize();
        if (GameProcessMarker.isRunning(normalizedGameRoot)) {
            throw new IOException(Messages.get("instanceRepair.sharedRunning"));
        }
        InstanceGameSettingsStore settings = new InstanceGameSettingsStore();
        Path instancesRoot = normalizedGameRoot.resolve("versions").normalize();
        FileUtil.validateExistingAncestors(normalizedGameRoot, instancesRoot);
        if (Files.isDirectory(instancesRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.list(instancesRoot)) {
                for (Path directory : paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .toList()) {
                    if (Files.isSymbolicLink(directory)) continue;
                    if (GameProcessMarker.isRunning(directory)) {
                        throw new IOException(Messages.get("instanceRepair.sharedRunning"));
                    }
                    InstanceGameSettings instanceSettings;
                    try {
                        FileUtil.validateExistingAncestors(directory, settings.settingsFile(directory));
                        instanceSettings = settings.load(directory);
                    } catch (IOException damagedSettings) {
                        throw new IOException(Messages.get("instanceRepair.settingsUnsafe"), damagedSettings);
                    }
                    if (instanceSettings.hasCustomDirectory()) {
                        try {
                            if (GameProcessMarker.isRunning(Path.of(instanceSettings.runningDirectory()))) {
                                throw new IOException(Messages.get("instanceRepair.sharedRunning"));
                            }
                        } catch (RuntimeException invalidPath) {
                            throw new IOException(Messages.get("instanceRepair.settingsInvalid"), invalidPath);
                        }
                    }
                }
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted()
                || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("Game repair cancelled");
        }
    }

    public record Result(GameInstallationVerifier.Report before,
                         GameInstallationVerifier.Report after) {
        public Result {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }

        public List<GameInstallationVerifier.Check> repaired() {
            return before.issues().stream().filter(old -> after.checks().stream().anyMatch(now ->
                    now.path().equals(old.path()) && now.status() == GameInstallationVerifier.Status.VERIFIED))
                    .toList();
        }

        public List<GameInstallationVerifier.Check> failed() {
            return after.issues().stream().filter(check -> check.status()
                    != GameInstallationVerifier.Status.UNVERIFIABLE).toList();
        }

        public List<GameInstallationVerifier.Check> unverifiable() {
            return after.issues().stream().filter(check -> check.status()
                    == GameInstallationVerifier.Status.UNVERIFIABLE).toList();
        }
    }
}
