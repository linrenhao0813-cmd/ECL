package com.ecl.ui;

import com.ecl.launcher.VersionManager;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.modrinth.download.ModrinthDownloader;
import com.ecl.modrinth.model.ContentDownloadResult;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.util.InstanceOperationLease;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.modrinth.provider.ContentSource;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Owns content download, import, and profile resolution. */
final class ContentDownloadWorkflow {
    private final LauncherUI ui;

    static String loaderDisplayName(String loader) {
        return LauncherPathService.loaderDisplayName(loader);
    }

    ContentDownloadWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    List<String> availableContentProfiles(ContentTarget target) {
        if (target.usesLoader()) {
            List<String> allowedLoaders = List.of(target.loaders);
            return ui.versionManager.getLocalVersionProfiles().stream()
                    .filter(profile -> allowedLoaders.contains(profile.loader()))
                    .map(VersionManager.LocalVersionProfile::profileId)
                    .distinct()
                    .toList();
        }
        if (ui.versionCombo == null) {
            return List.of();
        }
        return ui.versionCombo.getItems().stream()
                .filter(profileId -> profileId != null && !profileId.isBlank())
                .distinct()
                .toList();
    }

    ContentInstance resolveContentInstance(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("目标实例不能为空");
        }
        String loader = null;
        for (VersionManager.LocalVersionProfile profile : ui.versionManager.getLocalVersionProfiles()) {
            if (profile.profileId().equals(profileId)) {
                loader = profile.loader();
                break;
            }
        }
        try {
            return new ContentInstance(
                    profileId,
                    ui.versionManager.resolveMinecraftVersionId(profileId),
                    loader,
                    VersionProfileModInstanceContext.load(profileId,
                            com.ecl.ECLConfig.getVersionsDir().toPath(),
                            ui.getConfiguredGameRootDir().toPath(),
                            ui.resolveVersionGameDir(profileId).toPath()).instanceId(),
                    ui.resolveVersionGameDir(profileId));
        } catch (IOException error) {
            throw new IllegalArgumentException("无法解析目标实例 " + profileId, error);
        }
    }

    void updateContentTargetLabel(
            ContentTarget target,
            ContentInstance instance,
            Label targetLabel
    ) {
        File importDir = target.folderResolver.apply(instance.profileId());
        String loaderLabel = instance.loader() == null || instance.loader().isBlank()
                ? "原版/通用" : loaderDisplayName(instance.loader());
        targetLabel.setText("目标实例: " + ui.versionManager.getVersionDisplayName(instance.profileId())
                + "    Minecraft: " + instance.minecraftVersion()
                + "    加载器: " + loaderLabel
                + "    导入目录: " + importDir.getAbsolutePath());
    }

    DownloadTaskCenter.TaskHandle<?> downloadSelectedContent(
            ContentSource source,
            ContentTarget target,
            ContentProject project,
            ContentVersion selectedVersion,
            ContentInstance instance,
            File importDir,
            Label dialogStatus,
            ProgressBar modProgress,
            Button searchBtn,
            Button importBtn,
            ComboBox<String> targetProfileCombo,
            AtomicLong downloadGeneration,
            AtomicLong activeDownloadGeneration
    ) {
        if (project == null || selectedVersion == null) {
            dialogStatus.setText("请先选择一个" + target.title + "及其具体版本。");
            return null;
        }
        return ui.downloadTaskCenter.submit(
                "Content " + project.getTitle(), () -> {
            // This factory is invoked for the initial attempt and every retry. Keep all UI
            // preparation here so a retry cannot reuse stale listeners/progress from the failed
            // attempt or accidentally run alongside another content import.
            long generation = downloadGeneration.incrementAndGet();
            activeDownloadGeneration.set(generation);
            String loader = target.usesLoader() ? instance.loader() : null;
            String gameVersion = instance.minecraftVersion();
            ui.setControlsBusy(true);
            searchBtn.setDisable(true);
            importBtn.setDisable(true);
            targetProfileCombo.setDisable(true);
            modProgress.setProgress(0);
            ui.downloadProgress.setProgress(0);
            ui.startProgressAnimation(modProgress);
            ui.startProgressAnimation(ui.downloadProgress);
            String loaderLabel = loader == null ? "" : " / " + loader;
            ui.setStatus("正在下载" + target.title,
                    project.getTitle() + " " + selectedVersion.versionNumber()
                            + " -> " + gameVersion + loaderLabel);

            return context -> {
            if (generation != downloadGeneration.get()) {
                return null;
            }
            try {
                if (ui.isVersionRunning(instance.profileId())) {
                    throw new IOException("实例正在运行，不能安装内容: " + instance.profileId());
                }
                ContentDownloadResult result;
                MrpackInstaller.InstallResult packResult = null;
                try (AutoCloseable ignored = ui.controller.instanceOperations()
                        .acquireInterruptibly(instance.instanceId());
                     InstanceOperationLease instanceLock =
                             InstanceOperationLease.tryAcquire(
                                     instance.gameDirectory().toPath())) {
                    if (instanceLock == null) {
                        throw new IOException("实例内容操作正在另一个启动器进程中执行: "
                                + instance.profileId());
                    }
                    if (context.isCancelled()) {
                        throw new java.util.concurrent.CancellationException("内容安装已取消");
                    }
                    if (ui.isVersionRunning(instance.profileId())) {
                        throw new IOException("实例正在运行，不能安装内容: " + instance.profileId());
                    }
                result = ui.controller.contentDownloader(source).downloadVersion(
                        project, selectedVersion, gameVersion, loader, importDir,
                        target.downloadDependencies,
                        contentDownloadListener(context, generation, downloadGeneration,
                                dialogStatus, target, modProgress),
                        target.allowedExtensions
                );
                if (context.isCancelled()) {
                    throw new java.util.concurrent.CancellationException("内容安装已取消");
                }
                if ("modpack".equals(target.projectType)) {
                    if (result.getMainFile() == null) {
                        throw new IOException("整合包下载完成，但没有找到安装文件");
                    }
                    File installArchive = result.getMainFile();
                    boolean converted = source == ContentSource.CURSEFORGE;
                    if (converted) {
                        Platform.runLater(() -> {
                            if (generation == downloadGeneration.get()) {
                                dialogStatus.setText("正在解析 CurseForge 整合包清单...");
                            }
                        });
                        installArchive = ui.controller.curseForgeDownloader()
                                .convertModpackToMrpack(result.getMainFile());
                    }
                    try {
                        packResult = ui.mrpackInstaller.install(
                                installArchive,
                                ui.getConfiguredGameRootDir(),
                                project.getTitle(),
                                source == ContentSource.MODRINTH ? project.getProjectId() : "",
                                source == ContentSource.MODRINTH ? selectedVersion.versionId() : "",
                                new MrpackInstaller.Listener() {
                                    @Override
                                    public void onStatus(String message) {
                                        if (context.isCancelled()) {
                                            throw new java.util.concurrent.CancellationException("整合包安装已取消");
                                        }
                                        context.updateStatus(message);
                                        Platform.runLater(() -> {
                                            if (generation != downloadGeneration.get()) return;
                                            dialogStatus.setText(message);
                                            ui.setStatus("正在安装整合包", message);
                                        });
                                    }
                                    @Override
                                    public void onProgress(long downloaded, long total) {
                                        if (context.isCancelled()) {
                                            throw new java.util.concurrent.CancellationException("整合包安装已取消");
                                        }
                                        context.updateProgress(downloaded, total);
                                        Platform.runLater(() -> {
                                            if (generation != downloadGeneration.get()) return;
                                            ui.updateProgress(modProgress, downloaded, total);
                                            ui.updateProgress(ui.downloadProgress, downloaded, total);
                                        });
                                    }
                                });
                    } finally {
                        if (converted) Files.deleteIfExists(installArchive.toPath());
                    }
                    ui.gameRepository().applyDefaultIsolationSettingForNewInstance(packResult.profileId());
                }
                }

                MrpackInstaller.InstallResult completedPack = packResult;
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) return;
                    modProgress.setProgress(1);
                    ui.downloadProgress.setProgress(1);
                    activeDownloadGeneration.compareAndSet(generation, 0);
                    ui.stopProgressAnimation(modProgress, false);
                    ui.stopProgressAnimation(ui.downloadProgress, true);
                    ui.setControlsBusy(false);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(false);
                    targetProfileCombo.setDisable(false);
                    String mainFile = result.getMainFile() == null
                            ? project.getTitle() : result.getMainFile().getName();
                    String detail = completedPack == null
                            ? "已导入 " + result.getFiles().size() + " 个文件到: "
                                    + importDir.getAbsolutePath()
                            : "已安装为独立可启动实例 " + completedPack.profileId()
                                    + "，文件目录: " + completedPack.instanceDirectory();
                    dialogStatus.setText(mainFile + " 导入完成。 " + detail);
                    ui.setStatus(target.title + "导入完成", detail);
                    if (completedPack != null) {
                        ui.versionActions.restoreVersionComboItems(completedPack.profileId());
                        ui.versionActions.syncLaunchVersionToContent(completedPack.profileId());
                        dialogStatus.setText(mainFile + " 安装完成，正在启动整合包…");
                        ui.setStatus("整合包安装完成", "正在启动 " + completedPack.name());
                        Platform.runLater(() -> ui.gameLaunch.launchGame());
                    } else {
                        ui.versionActions.syncLaunchVersionToContent(instance.profileId());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) return;
                    String message = ui.cleanMessage(e);
                    activeDownloadGeneration.compareAndSet(generation, 0);
                    ui.stopProgressAnimation(modProgress, true);
                    ui.stopProgressAnimation(ui.downloadProgress, true);
                    ui.setControlsBusy(false);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(false);
                    targetProfileCombo.setDisable(false);
                    dialogStatus.setText("下载失败: " + message);
                    ui.setStatus(target.title + "下载失败", message);
                });
                throw e;
            }
            return null;
            };
        });
    }

    private ModrinthDownloader.DownloadListener contentDownloadListener(
            DownloadTaskCenter.TaskContext context, long generation, AtomicLong downloadGeneration,
            Label dialogStatus, ContentTarget target, ProgressBar modProgress) {
        return new ModrinthDownloader.DownloadListener() {
            @Override
            public void onStatus(String message) {
                requireActive(context, "内容安装已取消");
                context.updateStatus(message);
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) return;
                    dialogStatus.setText(message);
                    ui.setStatus("正在导入" + target.title, message);
                });
            }

            @Override
            public void onProgress(long downloaded, long total) {
                requireActive(context, "内容安装已取消");
                context.updateProgress(downloaded, total);
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) return;
                    ui.updateProgress(modProgress, downloaded, total);
                    ui.updateProgress(ui.downloadProgress, downloaded, total);
                });
            }
        };
    }

    private static void requireActive(DownloadTaskCenter.TaskContext context, String message) {
        if (context.isCancelled()) {
            throw new java.util.concurrent.CancellationException(message);
        }
    }
}
