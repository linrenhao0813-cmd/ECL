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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Checks one instance and repairs only authenticated Minecraft and loader dependency files. */
public final class GameInstallationRepairService {
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

    public GameInstallationVerifier.Report inspect(String profileId, Path versions,
                                                     Path libraries, Path assets, Path instancesRoot) {
        return new GameInstallationVerifier(versions, libraries, assets, instancesRoot).inspect(profileId);
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
        if (!canRepair(expected, target, versions)) {
            throw new IOException(Messages.get("instanceRepair.noAutoFix"));
        }
        requireTarget(instance, target, versions);
        requireNoGameRunning(instance, gameRoot);
        try (AutoCloseable ignored = operations.acquireInterruptibly(instance.instanceId());
             InstanceOperationLease processLock = InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
            if (processLock == null) throw new IOException(Messages.get("instanceRepair.busy"));
            requireNoGameRunning(instance, gameRoot);
            GameInstallationVerifier.Report current = inspect(instance.profileId(), versions, libraries, assets, gameRoot.resolve("versions"));
            if (!current.fingerprint().equals(expected.fingerprint())) {
                throw new IOException(Messages.get("instanceRepair.stale"));
            }
            checkCancelled(cancelled);
            try {
                if (current.issues().stream().anyMatch(check -> metadataRepairable(check, target, versions))) {
                    downloader.repairMetadata(target.downloadVersionId(), target.versionUrl(), target.versionSha1(), versions);
                }
                checkCancelled(cancelled);
                downloader.repairVerifiedFiles(instance.profileId(), versions, libraries, assets, gameRoot.resolve("versions"), listener);
                checkCancelled(cancelled);
                VersionMetadata metadata = new VersionRepository(versions.toFile()).resolve(instance.profileId());
                LegacyAssetMaterializer.prepare(metadata.assetIndex(), assets.toFile(), instance.gameDirectory().toFile());
            } catch (IOException failedDownload) {
                checkCancelled(cancelled);
                if (listener != null) listener.onError(failedDownload.getMessage());
            }
            checkCancelled(cancelled);
            GameInstallationVerifier.Report after = inspect(instance.profileId(), versions, libraries, assets, gameRoot.resolve("versions"));
            return new Result(expected, after);
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
            } catch (IOException | RuntimeException damaged) {
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

    /** Shared repair eligibility used by the report UI and the execution boundary. */
    public static boolean canRepair(GameInstallationVerifier.Report report,
                                    VersionManager.VersionDownloadTarget target, Path versions) {
        if (report == null || target == null || !report.profileId().equals(target.requestedProfileId())) return false;
        try {
            URI uri = URI.create(target.versionUrl());
            requireOfficialMetadataHost(uri);
            if (uri.getUserInfo() != null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()) && NetworkUriPolicy.isLoopbackHostLiteral(uri.getHost()))) return false;
            if (!target.versionSha1().matches("(?i)[0-9a-f]{40}")) return false;
            FileUtil.requireSafeVersionId(target.downloadVersionId());
            return report.issues().stream().anyMatch(check -> check.repairable()
                    || metadataRepairable(check, target, versions));
        } catch (IOException | RuntimeException invalidTarget) {
            return false;
        }
    }

    private static boolean metadataRepairable(GameInstallationVerifier.Check check,
                                              VersionManager.VersionDownloadTarget target, Path versions) {
        if (check.category() != GameInstallationVerifier.Category.METADATA
                || check.status() != GameInstallationVerifier.Status.MISSING
                && check.status() != GameInstallationVerifier.Status.CORRUPT) return false;
        try {
            FileUtil.requireSafeVersionId(target.downloadVersionId());
            Path file = versions.toAbsolutePath().normalize().resolve(target.downloadVersionId())
                    .resolve(target.downloadVersionId() + ".json");
            return Path.of(check.path()).toAbsolutePath().normalize().equals(file);
        } catch (IOException | RuntimeException invalidPath) {
            return false;
        }
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
