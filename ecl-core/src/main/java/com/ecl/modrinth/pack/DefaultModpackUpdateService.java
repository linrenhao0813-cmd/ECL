package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.service.DefaultInstanceOperationLock;
import com.ecl.modrinth.service.InstanceOperationLock;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Detects and downloads compatible Modrinth pack updates from persisted profile metadata. */
public final class DefaultModpackUpdateService implements ModpackUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultModpackUpdateService.class);
    private final ModMetadataProvider metadataProvider;
    private final Executor executor;
    private final InstanceOperationLock operationLock;
    private final Predicate<UUID> instanceRunning;
    private final HashVerifier hashVerifier = new HashVerifier();
    private final MrpackInstaller installer = new MrpackInstaller();

    public DefaultModpackUpdateService(ModMetadataProvider metadataProvider, Executor executor) {
        this(metadataProvider, executor, new DefaultInstanceOperationLock(), ignored -> false);
    }

    public DefaultModpackUpdateService(ModMetadataProvider metadataProvider, Executor executor,
                                       InstanceOperationLock operationLock,
                                       Predicate<UUID> instanceRunning) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
    }

    @Override
    public CompletableFuture<List<ModpackUpdate>> checkUpdates(Path gameRoot, ReleaseChannel channel) {
        Path root = normalizeGameRoot(gameRoot);
        ReleaseChannel effectiveChannel = channel == null ? ReleaseChannel.RELEASE_ONLY : channel;
        return CompletableFuture.supplyAsync(() -> scanInstalled(root), executor)
                .thenCompose(instances -> {
                    List<CompletableFuture<ModpackUpdate>> requests = instances.stream()
                            .map(instance -> checkOne(instance, effectiveChannel)
                                    .exceptionally(error -> {
                                        LOGGER.warn("Failed to check modpack updates for {}",
                                                instance.profileId(), error);
                                        return null;
                                    }))
                            .toList();
                    if (requests.isEmpty()) {
                        return CompletableFuture.completedFuture(List.of());
                    }
                    return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> requests.stream()
                                    .map(CompletableFuture::join)
                                    .filter(Objects::nonNull)
                                    .toList());
                });
    }

    private CompletableFuture<ModpackUpdate> checkOne(ModpackInstance instance,
                                                       ReleaseChannel channel) {
        return metadataProvider.getVersions(instance.projectId(), instance.minecraftVersion(),
                        instance.loader())
                .thenApply(versions -> selectUpdate(instance, versions, channel));
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
    public CompletableFuture<MrpackInstaller.InstallResult> applyUpdate(
            ModpackUpdate update, Path gameRoot, MrpackInstaller.Listener listener) {
        Objects.requireNonNull(update, "update");
        Path root = normalizeGameRoot(gameRoot);
        return CompletableFuture.supplyAsync(() -> {
            UUID instanceId = update.instance().instanceId();
            if (instanceRunning.test(instanceId)) {
                throw new java.util.concurrent.CompletionException(
                        new IOException("Instance is running and cannot be updated"));
            }
            Path temporary = null;
            try (AutoCloseable ignored = operationLock.acquire(instanceId)) {
                if (instanceRunning.test(instanceId)) {
                    throw new IOException("Instance is running and cannot be updated");
                }
                temporary = Files.createTempFile(ECLConfig.getBaseDir().toPath(),
                        "ecl-modpack-update-", ".mrpack");
                ModFile file = update.selectedFile();
                HttpUtil.downloadFileWithProgress(file.url().toString(), temporary.toFile(),
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
                        });
                hashVerifier.verify(temporary, file.hashes());
                if (instanceRunning.test(instanceId)) {
                    throw new IOException("Instance started while its modpack update was downloading");
                }
                return installer.update(temporary.toFile(), root.toFile(),
                        update.instance().profileId(), update.instance().projectId(),
                        update.availableVersion().id(),
                        () -> instanceRunning.test(instanceId), listener);
            } catch (Exception error) {
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

    private List<ModpackInstance> scanInstalled(Path gameRoot) {
        Path versionsRoot = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(versionsRoot)) {
            return List.of();
        }
        List<ModpackInstance> result = new ArrayList<>();
        try (var directories = Files.list(versionsRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String profileId = directory.getFileName().toString();
                try {
                    Path profileFile = FileUtil.safeVersionJson(versionsRoot.toFile(), profileId).toPath();
                    JsonObject profile = HttpUtil.readJson(profileFile.toFile());
                    String source = JsonUtil.getString(profile, "eclModpackSource", "");
                    String projectId = JsonUtil.getString(profile, "eclModpackProjectId", "");
                    String versionId = JsonUtil.getString(profile, "eclModpackVersionId", "");
                    if (!"modrinth".equalsIgnoreCase(source)
                            || projectId.isBlank() || versionId.isBlank()) {
                        continue;
                    }
                    String minecraft = JsonUtil.getString(profile, "eclMinecraftVersion", "");
                    if (minecraft.isBlank()) minecraft = JsonUtil.getString(profile, "inheritsFrom", "");
                    Path instanceDirectory = gameRoot.resolve("versions")
                            .resolve(profileId).normalize();
                    result.add(new ModpackInstance(
                            ModpackInstance.instanceIdFor(instanceDirectory),
                            profileId,
                            JsonUtil.getString(profile, "eclModpackName", profileId),
                            JsonUtil.getString(profile, "eclModpackVersion", versionId),
                            minecraft,
                            JsonUtil.getString(profile, "eclModLoader", ""),
                            projectId,
                            versionId,
                            instanceDirectory));
                } catch (IOException | RuntimeException ignored) {
                    // A damaged unrelated profile must not block updates for healthy packs.
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private static Path normalizeGameRoot(Path gameRoot) {
        return Objects.requireNonNull(gameRoot, "gameRoot").toAbsolutePath().normalize();
    }
}
