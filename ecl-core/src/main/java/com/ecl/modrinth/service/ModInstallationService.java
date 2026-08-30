package com.ecl.modrinth.service;

import com.ecl.modrinth.api.ModConflictException;
import com.ecl.modrinth.api.ModInstallationException;
import com.ecl.modrinth.download.DownloadedModFile;
import com.ecl.modrinth.download.ModDownloadProgress;
import com.ecl.modrinth.download.ModDownloadRequest;
import com.ecl.modrinth.download.ModFileDownloadService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.repository.InstalledModRepository;
import com.ecl.modrinth.transaction.FileModInstallationTransaction;
import com.ecl.modrinth.transaction.ModInstallationPlan;
import com.ecl.modrinth.transaction.PlannedModFile;
import com.ecl.util.FileUtil;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ModInstallationService {
    private static final long MIN_FREE_SPACE_AFTER_INSTALL = 32L * 1024 * 1024;

    private final InstalledModRepository repository;
    private final ModFileDownloadService downloadService;
    private final InstanceOperationLock operationLock;
    private final Executor orchestrationExecutor;
    private final Predicate<UUID> instanceRunning;

    public ModInstallationService(
            InstalledModRepository repository,
            ModFileDownloadService downloadService,
            InstanceOperationLock operationLock,
            Executor orchestrationExecutor,
            Predicate<UUID> instanceRunning
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.downloadService = Objects.requireNonNull(downloadService, "downloadService");
        this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
        this.orchestrationExecutor = Objects.requireNonNull(orchestrationExecutor, "orchestrationExecutor");
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
    }

    public CompletableFuture<ModInstallationResult> install(
            ModInstallationPlan plan,
            Consumer<ModDownloadProgress> progressListener
    ) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.installable()) {
            return CompletableFuture.failedFuture(new ModConflictException(
                    "安装计划包含 " + plan.conflicts().size() + " 个未解决冲突"));
        }
        return CompletableFuture.supplyAsync(
                () -> installBlocking(plan, progressListener), orchestrationExecutor);
    }

    private ModInstallationResult installBlocking(
            ModInstallationPlan plan,
            Consumer<ModDownloadProgress> progressListener
    ) {
        ModInstanceContext instance = plan.instance();
        if (instanceRunning.test(instance.instanceId())) {
            throw new ModInstallationException("实例正在运行，不能修改模组文件");
        }
        try (AutoCloseable ignored = operationLock.acquire(instance.instanceId())) {
            checkCancelled();
            prepareDirectoriesAndSpace(plan);
            FileModInstallationTransaction.recoverIncompleteTransactions(instance.gameDirectory());
            List<InstalledMod> existing = repository.findAll(instance);

            try (FileModInstallationTransaction transaction =
                         new FileModInstallationTransaction(instance.gameDirectory())) {
                Path downloadsDirectory = transaction.temporaryDirectory().resolve("downloads");
                Files.createDirectories(downloadsDirectory);
                List<ModDownloadRequest> requests = createRequests(plan.files(), downloadsDirectory);
                List<DownloadedModFile> downloads =
                        downloadService.downloadAll(requests, progressListener).join();
                checkCancelled();

                Map<String, DownloadedModFile> byVersionId = new HashMap<>();
                for (DownloadedModFile download : downloads) {
                    byVersionId.put(download.request().fileName(), download);
                }
                stageModFiles(plan, existing, transaction, byVersionId);
                List<InstalledMod> updatedIndex = updateIndex(plan, existing, byVersionId);
                Path stagedIndex = repository.createSnapshot(
                        instance, updatedIndex, transaction.temporaryDirectory());
                transaction.stageDownloadedFile(stagedIndex, repository.indexPath(instance));
                transaction.commit();

                Set<String> previousProjects = new HashSet<>();
                existing.forEach(mod -> previousProjects.add(mod.projectId()));
                boolean updated = plan.files().stream()
                        .anyMatch(file -> previousProjects.contains(file.version().projectId()));
                List<InstalledMod> installed = updatedIndex.stream()
                        .filter(mod -> plan.files().stream()
                                .anyMatch(file -> file.version().projectId().equals(mod.projectId())))
                        .toList();
                return new ModInstallationResult(installed, updated);
            }
        } catch (CompletionException e) {
            throw installationFailure(e.getCause() == null ? e : e.getCause());
        } catch (ModInstallationException | ModConflictException e) {
            throw e;
        } catch (Exception e) {
            // Broad catch is required: operationLock AutoCloseable.close() declares Exception.
            throw installationFailure(e);
        }
    }

    private void prepareDirectoriesAndSpace(ModInstallationPlan plan) throws IOException {
        Path mods = plan.instance().modsDirectory();
        FileUtil.validateExistingAncestors(plan.instance().gameDirectory(), mods);
        Files.createDirectories(mods);
        if (!Files.isWritable(mods)) {
            throw new ModInstallationException("实例 mods 目录不可写: " + mods);
        }
        FileStore store = Files.getFileStore(mods);
        long required = Math.addExact(Math.max(0, plan.totalDownloadSize()), MIN_FREE_SPACE_AFTER_INSTALL);
        if (store.getUsableSpace() < required) {
            throw new ModInstallationException("磁盘空间不足，需要至少 "
                    + required + " 字节可用空间");
        }
    }

    private List<ModDownloadRequest> createRequests(List<PlannedModFile> files, Path downloadsDirectory) {
        List<ModDownloadRequest> requests = new ArrayList<>(files.size());
        for (PlannedModFile planned : files) {
            Path temp = downloadsDirectory.resolve(UUID.randomUUID() + ".part");
            requests.add(new ModDownloadRequest(
                    planned.file().url(),
                    planned.file().fileName(),
                    temp,
                    planned.file().hashes(),
                    planned.file().size()));
        }
        return requests;
    }

    private void stageModFiles(
            ModInstallationPlan plan,
            List<InstalledMod> existing,
            FileModInstallationTransaction transaction,
            Map<String, DownloadedModFile> downloads
    ) throws IOException {
        Map<String, InstalledMod> existingByProject = new HashMap<>();
        existing.forEach(mod -> existingByProject.put(mod.projectId(), mod));
        Set<Path> knownPaths = new HashSet<>();
        existing.forEach(mod -> knownPaths.add(
                plan.instance().gameDirectory().resolve(mod.relativePath()).toAbsolutePath().normalize()));

        for (PlannedModFile planned : plan.files()) {
            DownloadedModFile downloaded = requireDownload(downloads, planned);
            Path target = planned.targetPath().toAbsolutePath().normalize();
            InstalledMod previous = existingByProject.get(planned.version().projectId());
            Path oldPath = previous == null ? null
                    : plan.instance().gameDirectory().resolve(previous.relativePath()).toAbsolutePath().normalize();
            if (Files.exists(target) && !knownPaths.contains(target)
                    && (oldPath == null || !oldPath.equals(target))) {
                throw new ModInstallationException("目标文件已存在且不属于受控安装记录: " + target);
            }
            if (oldPath != null && Files.exists(oldPath)) {
                transaction.stageReplacement(oldPath, downloaded.temporaryFile(), target);
            } else {
                transaction.stageDownloadedFile(downloaded.temporaryFile(), target);
            }
        }
    }

    private List<InstalledMod> updateIndex(
            ModInstallationPlan plan,
            List<InstalledMod> existing,
            Map<String, DownloadedModFile> downloads
    ) {
        Set<String> replacingProjects = new HashSet<>();
        plan.files().forEach(file -> replacingProjects.add(file.version().projectId()));
        List<InstalledMod> result = new ArrayList<>();
        existing.stream()
                .filter(mod -> !replacingProjects.contains(mod.projectId()))
                .forEach(result::add);
        Instant now = Instant.now();
        for (PlannedModFile planned : plan.files()) {
            DownloadedModFile download = requireDownload(downloads, planned);
            InstalledMod old = existing.stream()
                    .filter(mod -> planned.version().projectId().equals(mod.projectId()))
                    .findFirst().orElse(null);
            Path relative = plan.instance().gameDirectory().relativize(
                    planned.targetPath().toAbsolutePath().normalize());
            result.add(new InstalledMod(
                    plan.instance().instanceId(),
                    planned.version().projectId(),
                    planned.version().id(),
                    "",
                    firstNonBlank(planned.version().name(), planned.version().projectId()),
                    planned.version().versionNumber(),
                    planned.file().fileName(),
                    relative,
                    download.hashes().sha1(),
                    download.hashes().sha512(),
                    download.size(),
                    plan.instance().minecraftVersion(),
                    plan.instance().loaderName(),
                    planned.version().versionType(),
                    true,
                    planned.dependency(),
                    planned.requiredByProjectId(),
                    old == null || old.installedAt() == null ? now : old.installedAt(),
                    now));
        }
        return List.copyOf(result);
    }

    private static DownloadedModFile requireDownload(
            Map<String, DownloadedModFile> downloads, PlannedModFile planned) {
        DownloadedModFile file = downloads.get(planned.file().fileName());
        if (file == null) {
            throw new ModInstallationException("缺少已下载文件: " + planned.file().fileName());
        }
        return file;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ModInstallationException("模组下载已取消");
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static ModInstallationException installationFailure(Throwable error) {
        if (error instanceof ModInstallationException installationException) {
            return installationException;
        }
        return new ModInstallationException(
                error == null || error.getMessage() == null
                        ? "模组安装失败" : error.getMessage(),
                error);
    }
}
