package com.ecl.modrinth.service;

import com.ecl.modrinth.api.ModConflictException;
import com.ecl.modrinth.api.ModInstallationException;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.repository.InstalledModRepository;
import com.ecl.modrinth.transaction.FileModInstallationTransaction;
import com.ecl.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.jar.JarFile;

public final class DefaultModManagementService implements ModManagementService {
    private final InstalledModRepository repository;
    private final InstanceOperationLock operationLock;
    private final Executor executor;
    private final Predicate<UUID> instanceRunning;
    private final HashVerifier hashVerifier;

    public DefaultModManagementService(
            InstalledModRepository repository,
            InstanceOperationLock operationLock,
            Executor executor,
            Predicate<UUID> instanceRunning,
            HashVerifier hashVerifier
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
        this.hashVerifier = Objects.requireNonNull(hashVerifier, "hashVerifier");
    }

    @Override
    public CompletableFuture<List<InstalledMod>> list(ModInstanceContext instance) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.findAll(instance);
            } catch (IOException e) {
                throw new ModInstallationException("无法读取模组安装记录", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<InstalledMod>> setEnabled(
            ModInstanceContext instance, Collection<String> projectIds, boolean enabled) {
        Set<String> selected = normalizedIds(projectIds);
        return CompletableFuture.supplyAsync(() -> changeEnabled(instance, selected, enabled), executor);
    }

    private List<InstalledMod> changeEnabled(ModInstanceContext instance, Set<String> selected, boolean enabled) {
        ensureWritable(instance);
        try (AutoCloseable ignored = operationLock.acquire(instance.instanceId())) {
            FileModInstallationTransaction.recoverIncompleteTransactions(instance.gameDirectory());
            List<InstalledMod> records = repository.findAll(instance);
            if (!enabled) {
                verifyNotRequired(records, selected, "禁用");
            } else {
                verifyCanEnable(instance, records, selected);
            }
            try (FileModInstallationTransaction transaction =
                         new FileModInstallationTransaction(instance.gameDirectory())) {
                List<InstalledMod> updated = new ArrayList<>(records.size());
                for (InstalledMod record : records) {
                    if (!selected.contains(record.projectId()) || record.enabled() == enabled) {
                        updated.add(record);
                        continue;
                    }
                    Path source = resolveRecordPath(instance, record);
                    if (!Files.isRegularFile(source)) {
                        throw new ModInstallationException("模组文件不存在: " + source);
                    }
                    Path destinationDirectory = enabled
                            ? instance.modsDirectory()
                            : instance.gameDirectory().resolve("disabled-mods");
                    try {
                        FileUtil.validateExistingAncestors(instance.gameDirectory(), destinationDirectory);
                    } catch (IOException error) {
                        throw new ModInstallationException(error.getMessage(), error);
                    }
                    Files.createDirectories(destinationDirectory);
                    Path target = destinationDirectory.resolve(record.fileName()).normalize();
                    ensureInside(instance.gameDirectory(), target);
                    if (Files.exists(target)) {
                        throw new ModConflictException("目标文件已存在: " + target);
                    }
                    Path staged = transaction.temporaryDirectory()
                            .resolve("moves").resolve(UUID.randomUUID() + ".jar");
                    Files.createDirectories(staged.getParent());
                    Files.copy(source, staged, StandardCopyOption.COPY_ATTRIBUTES);
                    transaction.stageReplacement(source, staged, target);
                    updated.add(copyState(instance, record, enabled, target));
                }
                Path index = repository.createSnapshot(instance, updated, transaction.temporaryDirectory());
                transaction.stageDownloadedFile(index, repository.indexPath(instance));
                transaction.commit();
                return List.copyOf(updated);
            }
        } catch (ModInstallationException | ModConflictException e) {
            throw e;
        } catch (Exception e) {
            // Broad catch is required: operationLock AutoCloseable.close() declares Exception.
            throw new ModInstallationException((enabled ? "启用" : "禁用") + "模组失败", e);
        }
    }

    @Override
    public CompletableFuture<List<InstalledMod>> uninstall(
            ModInstanceContext instance, Collection<String> projectIds) {
        Set<String> selected = normalizedIds(projectIds);
        return CompletableFuture.supplyAsync(() -> uninstallBlocking(instance, selected), executor);
    }

    private List<InstalledMod> uninstallBlocking(ModInstanceContext instance, Set<String> selected) {
        ensureWritable(instance);
        try (AutoCloseable ignored = operationLock.acquire(instance.instanceId())) {
            FileModInstallationTransaction.recoverIncompleteTransactions(instance.gameDirectory());
            List<InstalledMod> records = repository.findAll(instance);
            verifyNotRequired(records, selected, "卸载");
            try (FileModInstallationTransaction transaction =
                         new FileModInstallationTransaction(instance.gameDirectory())) {
                Path trash = instance.gameDirectory().resolve("launcher-trash")
                        .resolve(transaction.temporaryDirectory().getFileName().toString());
                List<InstalledMod> remaining = new ArrayList<>();
                for (InstalledMod record : records) {
                    if (!selected.contains(record.projectId())) {
                        remaining.add(record);
                        continue;
                    }
                    Path source = resolveRecordPath(instance, record);
                    if (Files.isRegularFile(source)) {
                        Path staged = transaction.temporaryDirectory()
                                .resolve("uninstall").resolve(UUID.randomUUID() + ".jar");
                        Files.createDirectories(staged.getParent());
                        Files.copy(source, staged, StandardCopyOption.COPY_ATTRIBUTES);
                        Path trashTarget = trash.resolve(record.fileName()).normalize();
                        ensureInside(instance.gameDirectory(), trashTarget);
                        transaction.stageReplacement(source, staged, trashTarget);
                    }
                }
                Path index = repository.createSnapshot(instance, remaining, transaction.temporaryDirectory());
                transaction.stageDownloadedFile(index, repository.indexPath(instance));
                transaction.commit();
                deleteTreeQuietly(trash);
                return List.copyOf(remaining);
            }
        } catch (ModInstallationException | ModConflictException e) {
            throw e;
        } catch (Exception e) {
            // Broad catch is required: operationLock AutoCloseable.close() declares Exception.
            throw new ModInstallationException("卸载模组失败", e);
        }
    }

    @Override
    public CompletableFuture<InstalledMod> importLocalJar(ModInstanceContext instance, Path jarFile) {
        return CompletableFuture.supplyAsync(() -> importBlocking(instance, jarFile), executor);
    }

    private InstalledMod importBlocking(ModInstanceContext instance, Path jarFile) {
        ensureWritable(instance);
        Path source = jarFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new ModInstallationException("请选择有效的 JAR 文件");
        }
        try (JarFile ignoredJar = new JarFile(source.toFile());
             AutoCloseable ignored = operationLock.acquire(instance.instanceId());
             FileModInstallationTransaction transaction =
                     new FileModInstallationTransaction(instance.gameDirectory())) {
            try {
                FileUtil.validateExistingAncestors(instance.gameDirectory(), instance.modsDirectory());
            } catch (IOException error) {
                throw new ModInstallationException(error.getMessage(), error);
            }
            Files.createDirectories(instance.modsDirectory());
            Path target = instance.modsDirectory().resolve(source.getFileName().toString()).normalize();
            ensureInside(instance.modsDirectory(), target);
            if (Files.exists(target)) {
                throw new ModConflictException("mods 中已存在同名文件: " + target.getFileName());
            }
            Path staged = transaction.temporaryDirectory().resolve("import").resolve(source.getFileName());
            Files.createDirectories(staged.getParent());
            Files.copy(source, staged, StandardCopyOption.COPY_ATTRIBUTES);
            HashVerifier.HashResult hashes = hashVerifier.calculate(staged);
            transaction.stageDownloadedFile(staged, target);

            List<InstalledMod> records = new ArrayList<>(repository.findAll(instance));
            Instant now = Instant.now();
            InstalledMod imported = new InstalledMod(
                    instance.instanceId(), "local:" + hashes.sha1(), "", "",
                    source.getFileName().toString(), "", source.getFileName().toString(),
                    instance.gameDirectory().relativize(target), hashes.sha1(), hashes.sha512(),
                    Files.size(source), instance.minecraftVersion(), instance.loaderName(), "local",
                    true, false, "", now, now);
            records.add(imported);
            Path index = repository.createSnapshot(instance, records, transaction.temporaryDirectory());
            transaction.stageDownloadedFile(index, repository.indexPath(instance));
            transaction.commit();
            return imported;
        } catch (ModInstallationException | ModConflictException e) {
            throw e;
        } catch (Exception e) {
            // Broad catch is required: JarFile + operationLock close() declare Exception.
            throw new ModInstallationException("导入本地模组失败", e);
        }
    }

    private void ensureWritable(ModInstanceContext instance) {
        if (instanceRunning.test(instance.instanceId())) {
            throw new ModInstallationException("实例正在运行，不能修改模组文件");
        }
    }

    private static void verifyNotRequired(List<InstalledMod> records, Set<String> selected, String operation) {
        for (String projectId : selected) {
            List<String> ownerIds = records.stream()
                    .filter(mod -> projectId.equals(mod.projectId()))
                    .filter(InstalledMod::dependency)
                    .map(InstalledMod::requiredByProjectId)
                    .filter(owner -> owner != null && !owner.isBlank())
                    .distinct()
                    .toList();
            List<String> dependents = records.stream()
                    .filter(InstalledMod::enabled)
                    .filter(mod -> ownerIds.contains(mod.projectId()))
                    .filter(mod -> !selected.contains(mod.projectId()))
                    .map(mod -> mod.displayName() == null || mod.displayName().isBlank()
                            ? mod.projectId() : mod.displayName())
                    .toList();
            if (!dependents.isEmpty()) {
                throw new ModConflictException("无法" + operation + " " + projectId
                        + "，以下已启用模组依赖它: " + String.join("、", dependents));
            }
        }
    }

    private static void verifyCanEnable(
            ModInstanceContext instance, List<InstalledMod> records, Set<String> selected) {
        for (InstalledMod mod : records) {
            if (!selected.contains(mod.projectId())) {
                continue;
            }
            if (!instance.minecraftVersion().equals(mod.minecraftVersion())
                    || !instance.loaderName().equalsIgnoreCase(mod.loader())) {
                throw new ModConflictException("无法启用 " + mod.displayName()
                        + "：模组记录与当前 Minecraft 版本或加载器不兼容");
            }
            List<String> missingDependencies = records.stream()
                    .filter(InstalledMod::dependency)
                    .filter(dependency -> mod.projectId().equals(dependency.requiredByProjectId()))
                    .filter(dependency -> !dependency.enabled()
                            && !selected.contains(dependency.projectId()))
                    .map(dependency -> dependency.displayName() == null
                            || dependency.displayName().isBlank()
                            ? dependency.projectId() : dependency.displayName())
                    .toList();
            if (!missingDependencies.isEmpty()) {
                throw new ModConflictException("无法启用 " + mod.displayName()
                        + "，required 依赖未启用: " + String.join("、", missingDependencies));
            }
        }
    }

    private static InstalledMod copyState(
            ModInstanceContext instance, InstalledMod source, boolean enabled, Path newPath) {
        return new InstalledMod(
                source.instanceId(), source.projectId(), source.versionId(), source.projectSlug(),
                source.displayName(), source.versionNumber(), source.fileName(),
                instance.gameDirectory().relativize(newPath.toAbsolutePath().normalize()),
                source.sha1(), source.sha512(), source.fileSize(), source.minecraftVersion(),
                source.loader(), source.versionType(), enabled, source.dependency(),
                source.requiredByProjectId(), source.installedAt(), Instant.now());
    }

    private static Path resolveRecordPath(ModInstanceContext instance, InstalledMod record) {
        Path result = instance.gameDirectory().resolve(record.relativePath()).toAbsolutePath().normalize();
        ensureInside(instance.gameDirectory(), result);
        return result;
    }

    private static Set<String> normalizedIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个模组");
        }
        Set<String> result = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("模组项目 ID 不能为空");
            }
            result.add(id.trim());
        }
        return Set.copyOf(result);
    }

    private static void ensureInside(Path root, Path target) {
        if (!target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new ModInstallationException("文件路径超出实例目录: " + target);
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Trash remains recoverable and can be cleaned on the next startup.
        }
    }
}
