package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.modrinth.service.InstanceOperationLock;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.UUID;

/** Detects and downloads compatible Modrinth pack updates for a selected instance. */
public final class DefaultModpackUpdateService implements ModpackUpdateService {
    private static final long MAX_PACK_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final java.util.concurrent.atomic.AtomicBoolean STALE_PREVIEWS_CLEANED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final ModMetadataProvider metadataProvider;
    private final Executor executor;
    private final InstanceOperationLock operationLock;
    private final Predicate<UUID> instanceRunning;
    private final HashVerifier hashVerifier = new HashVerifier();
    private final MrpackInstaller installer = new MrpackInstaller();

    public DefaultModpackUpdateService(ModMetadataProvider metadataProvider, Executor executor) {
        this(metadataProvider, executor, new InstanceOperationCoordinator(), ignored -> false);
    }

    public DefaultModpackUpdateService(ModMetadataProvider metadataProvider, Executor executor,
                                       InstanceOperationLock operationLock,
                                       Predicate<UUID> instanceRunning) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
        cleanStalePreviewsOnce();
    }

    /**
     * Best-effort startup cleanup of preview directories abandoned by a crashed update. Runs once
     * per JVM; failures must never prevent update checks from working.
     */
    private static void cleanStalePreviewsOnce() {
        if (!STALE_PREVIEWS_CLEANED.compareAndSet(false, true)) {
            return;
        }
        try {
            MrpackInstaller.cleanStalePreviews(MrpackInstaller.STALE_PREVIEW_AGE);
        } catch (RuntimeException ignored) {
            // Stale previews are a disk-space nuisance, never a functional requirement.
        }
    }

    @Override
    public CompletableFuture<ModpackUpdate> checkUpdate(ModpackInstance instance,
                                                      ReleaseChannel channel) {
        return metadataProvider.getVersions(instance.projectId(), instance.minecraftVersion(),
                        instance.loader())
                .thenApply(versions -> selectUpdate(instance, versions,
                        channel == null ? ReleaseChannel.RELEASE_ONLY : channel));
    }

    private ModpackUpdate selectUpdate(ModpackInstance instance, List<ModVersion> versions,
                                       ReleaseChannel channel) {
        List<ModVersion> compatible = versions == null ? List.of() : versions.stream()
                .filter(Objects::nonNull)
                .filter(version -> channel.allows(version.versionType()))
                .filter(version -> version.status() == null || version.status().isBlank()
                        || "listed".equalsIgnoreCase(version.status()))
                .filter(version -> version.gameVersions().contains(instance.minecraftVersion()))
                .filter(version -> instance.loader().isBlank()
                        || version.loaders().stream().anyMatch(instance.loader()::equalsIgnoreCase))
                .sorted(Comparator.comparing(
                        (ModVersion version) -> version.publishedAt() == null
                                ? Instant.EPOCH : version.publishedAt()).reversed())
                .toList();
        if (compatible.isEmpty() || compatible.getFirst().id().equals(instance.versionId())) {
            return null;
        }
        ModVersion latest = compatible.getFirst();
        ModFile file = selectPackFile(latest);
        if (file != null) {
            return new ModpackUpdate(instance, latest, file);
        }
        return null;
    }

    private ModFile selectPackFile(ModVersion version) {
        return version.files().stream()
                .filter(file -> file != null && file.url() != null)
                .filter(file -> file.fileName() != null
                        && file.fileName().toLowerCase(Locale.ROOT).endsWith(".mrpack"))
                .sorted(Comparator.comparing(ModFile::primary).reversed())
                .findFirst()
                .orElse(null);
    }

    @Override
    public CompletableFuture<PreparedUpdate> prepareUpdate(ModpackUpdate update, Path gameRoot,
                                                           MrpackInstaller.Listener listener) {
        Objects.requireNonNull(update, "update");
        Path root = normalizeGameRoot(gameRoot);
        return CompletableFuture.supplyAsync(() -> {
            Path work = null;
            try {
                MrpackInstaller.cleanStalePreviews(MrpackInstaller.STALE_PREVIEW_AGE, listener);
                if (isRunning(update.instance())) {
                    throw new IOException("Instance is running and cannot be inspected for update");
                }
                work = Files.createTempDirectory(ECLConfig.getBaseDir().toPath(),
                        MrpackInstaller.PREVIEW_DIRECTORY_PREFIX);
                Path archive = downloadArchive(update, work.resolve("update.mrpack"), listener);
                MrpackInstaller.UpdatePreview preview = installer.prepareUpdate(archive.toFile(),
                        root.toFile(), update.instance().profileId(), update.instance().projectId(),
                        update.availableVersion().id(), work, listener);
                if (isRunning(update.instance())) {
                    preview.close();
                    throw new IOException("Instance started while its modpack update was being prepared");
                }
                return new PreparedUpdate(update, preview);
            } catch (Exception error) {
                if (work != null) MrpackPathPolicy.deleteRecursively(work);
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<MrpackInstaller.InstallResult> applyPreparedUpdate(
            PreparedUpdate prepared, Path gameRoot, MrpackInstaller.Listener listener) {
        return applyPreparedUpdate(prepared, gameRoot, listener, null);
    }

    @Override
    public CompletableFuture<MrpackInstaller.InstallResult> applyPreparedUpdate(
            PreparedUpdate prepared, Path gameRoot, MrpackInstaller.Listener listener,
            com.ecl.operation.InstanceOperationSession session) {
        Objects.requireNonNull(prepared, "prepared");
        Path root = normalizeGameRoot(gameRoot);
        ModpackUpdate update = prepared.update();
        return CompletableFuture.supplyAsync(() -> {
            UUID instanceId = update.instance().instanceId();
            try (AutoCloseable ignored = session == null ? operationLock.acquire(instanceId)
                    : session.borrow(instanceId, update.instance().instanceDirectory())) {
                if (isRunning(update.instance())) {
                    throw new IOException("Instance is running and cannot be updated");
                }
                return installer.applyPreparedUpdate(prepared.preview(), root.toFile(),
                        () -> instanceRunning.test(instanceId), listener, session);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            } finally {
                prepared.closeQuietly();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<MrpackInstaller.InstallResult> applyUpdate(
            ModpackUpdate update, Path gameRoot, MrpackInstaller.Listener listener) {
        Objects.requireNonNull(update, "update");
        Path root = normalizeGameRoot(gameRoot);
        return CompletableFuture.supplyAsync(() -> {
            UUID instanceId = update.instance().instanceId();
            if (isRunning(update.instance())) {
                throw new java.util.concurrent.CompletionException(
                        new IOException("Instance is running and cannot be updated"));
            }
            Path temporary = null;
            try (AutoCloseable ignored = operationLock.acquire(instanceId)) {
                if (isRunning(update.instance())) {
                    throw new IOException("Instance is running and cannot be updated");
                }
                temporary = Files.createTempFile(ECLConfig.getBaseDir().toPath(),
                        "ecl-modpack-update-", ".mrpack");
                ModFile file = update.selectedFile();
                if (!HashVerifier.hasUsableExpectedHash(file.hashes())) {
                    throw new IOException("Modpack update is missing SHA-512 or SHA-1");
                }
                if (file.size() <= 0 || file.size() > MAX_PACK_ARCHIVE_BYTES) {
                    throw new IOException("Modpack update has an invalid declared size");
                }
                java.net.URI downloadUri = NetworkUriPolicy.requireAllowedDownload(
                        file.url(), MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS,
                        "Modpack update URL");
                HttpUtil.downloadFileWithProgress(downloadUri.toString(), temporary.toFile(),
                        new HttpUtil.ProgressCallback() {
                            @Override
                            public void onStart(long total) {
                                if (listener != null) listener.onProgress(0, total);
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                if (listener != null) listener.onProgress(downloaded, total);
                            }

                            @Override
                            public void onComplete(java.io.File ignored) {
                                if (listener != null) listener.onProgress(1, 1);
                            }
                        }, null, file.size(), MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS);
                if (Files.size(temporary) != file.size()) {
                    throw new IOException("Modpack update size does not match metadata");
                }
                hashVerifier.verify(temporary, file.hashes());
                if (isRunning(update.instance())) {
                    throw new IOException("Instance started while its modpack update was downloading");
                }
                return installer.update(temporary.toFile(), root.toFile(),
                        update.instance().profileId(), update.instance().projectId(),
                        update.availableVersion().id(),
                        () -> instanceRunning.test(instanceId), listener);
            } catch (Exception error) {
                // Broad catch is required: operationLock.acquire() returns AutoCloseable whose close() declares Exception,
                // and the install transaction may throw multiple checked types. Preserve full failure for CompletionException.
                throw new java.util.concurrent.CompletionException(error);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // The next update check can safely clean up a stale temporary archive.
                    }
                }
            }
        }, executor);
    }

    private static Path normalizeGameRoot(Path gameRoot) {
        return Objects.requireNonNull(gameRoot, "gameRoot").toAbsolutePath().normalize();
    }

    private Path downloadArchive(ModpackUpdate update, Path target,
                                 MrpackInstaller.Listener listener) throws IOException {
        ModFile file = update.selectedFile();
        if (!HashVerifier.hasUsableExpectedHash(file.hashes())) {
            throw new IOException("Modpack update is missing SHA-512 or SHA-1");
        }
        if (file.size() <= 0 || file.size() > MAX_PACK_ARCHIVE_BYTES) {
            throw new IOException("Modpack update has an invalid declared size");
        }
        java.net.URI downloadUri = NetworkUriPolicy.requireAllowedDownload(
                file.url(), MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS,
                "Modpack update URL");
        HttpUtil.downloadFileWithProgress(downloadUri.toString(), target.toFile(),
                new HttpUtil.ProgressCallback() {
                    @Override public void onStart(long total) {
                        if (listener != null) listener.onProgress(0, total);
                    }

                    @Override public void onProgress(long downloaded, long total) {
                        if (listener != null) listener.onProgress(downloaded, total);
                    }

                    @Override public void onComplete(java.io.File ignored) {
                        if (listener != null) listener.onProgress(1, 1);
                    }
                }, null, file.size(), MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS);
        if (Files.size(target) != file.size()) {
            throw new IOException("Modpack update size does not match metadata");
        }
        hashVerifier.verify(target, file.hashes());
        return target;
    }
    private boolean isRunning(ModpackInstance instance) {
        try {
            return instanceRunning.test(instance.instanceId())
                    || com.ecl.launch.GameProcessMarker.isRunning(instance.instanceDirectory());
        } catch (IOException failure) {
            throw new java.util.concurrent.CompletionException(failure);
        }
    }
}
