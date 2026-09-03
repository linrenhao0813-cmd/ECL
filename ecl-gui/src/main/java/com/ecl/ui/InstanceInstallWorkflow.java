package com.ecl.ui;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.GameDownloader;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.download.ModrinthDownloader;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ModProject;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;

/** Installs a selected base version, optional loader, and Fabric API as one download task. */
final class InstanceInstallWorkflow {
    private static final String FABRIC_API_SLUG = "fabric-api";

    interface Listener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
        void onComplete(String profileId);
        void onFailure(String message);
    }

    private final LauncherUI ui;

    InstanceInstallWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    void install(String minecraftVersion, LoaderChoice choice, Listener listener) {
        if (minecraftVersion == null || minecraftVersion.isBlank() || choice == null) {
            listener.onFailure("请选择 Minecraft 版本和实例类型。");
            return;
        }
        ui.setControlsBusy(true);
        ui.downloadProgress.setProgress(0);
        ui.startProgressAnimation(ui.downloadProgress);
        String title = "Minecraft " + minecraftVersion + " / " + choice.displayName;
        DownloadTaskCenter.TaskHandle<String> task = ui.downloadTaskCenter.submit(
                title, () -> context -> install(context, minecraftVersion, choice, listener));
        task.completion().whenComplete((profileId, error) -> Platform.runLater(() -> {
            ui.stopProgressAnimation(ui.downloadProgress, error != null);
            ui.setControlsBusy(false);
            if (error != null) {
                String message = ui.cleanMessage(error);
                listener.onFailure(message);
                ui.setStatus(ui.isCancellation(error) ? "实例安装已取消" : "实例安装失败", message);
                return;
            }
            ui.versionActions.restoreVersionComboItems(profileId);
            ui.versionCombo.setValue(profileId);
            ui.syncLoaderChoiceFromProfile(profileId);
            listener.onComplete(profileId);
            ui.setStatus("实例安装完成", profileId + " 已准备就绪。");
        }));
    }

    private String install(DownloadTaskCenter.TaskContext context, String minecraftVersion,
                           LoaderChoice choice, Listener listener) throws Exception {
        installBaseVersion(context, minecraftVersion, listener);
        requireActive(context);
        String profileId = minecraftVersion;
        if (!choice.vanilla()) {
            profileId = installLoader(context, minecraftVersion, choice, listener);
        }
        ui.gameRepository().applyDefaultIsolationSettingForNewInstance(profileId);
        if (choice == LoaderChoice.FABRIC) {
            installFabricApi(context, minecraftVersion, profileId, listener);
        }
        requireActive(context);
        return profileId;
    }

    private void installBaseVersion(DownloadTaskCenter.TaskContext context, String minecraftVersion,
                                    Listener listener) throws Exception {
        emitStatus(context, listener, "正在检查 Minecraft " + minecraftVersion + " 基础文件…");
        if (Boolean.TRUE.equals(ui.versionManager.ensureVersionDownloadedAsync(minecraftVersion).get())) {
            emitStatus(context, listener, "Minecraft " + minecraftVersion + " 已安装，跳过基础下载。");
            return;
        }
        VersionManager.VersionDownloadTarget target = ui.versionManager.resolveDownloadTarget(minecraftVersion);
        if (target.versionUrl().isBlank()) {
            throw new IOException("Mojang 版本清单中没有 " + minecraftVersion + " 的下载地址");
        }
        ui.downloader.setListener(new GameDownloader.DownloadListener() {
            @Override
            public void onStatus(String message) {
                emitStatus(context, listener, message);
            }

            @Override
            public void onProgress(long downloaded, long total) {
                emitProgress(context, listener, downloaded, total);
            }

            @Override
            public void onError(String message) {
                emitStatus(context, listener, message);
            }

            @Override
            public void onComplete() {
                emitStatus(context, listener, "Minecraft 基础文件下载完成。");
            }
        });
        context.registerCancellation(ui.downloader::cancelDownload);
        Future<?> future = ui.downloader.downloadVersionAsync(
                target.downloadVersionId(), target.versionUrl(), target.versionSha1());
        future.get();
        if (!ui.versionManager.isVersionDownloaded(minecraftVersion)) {
            throw new IOException("Minecraft " + minecraftVersion + " 下载后仍未通过完整性检查");
        }
    }

    private String installLoader(DownloadTaskCenter.TaskContext context, String minecraftVersion,
                                 LoaderChoice choice, Listener listener) throws IOException {
        emitStatus(context, listener, "正在安装 " + choice.displayName + "…");
        ModLoaderInstaller.InstallResult result = ui.modLoaderInstaller.install(
                minecraftVersion, choice.loader, "", new ModLoaderInstaller.Listener() {
                    @Override
                    public void onStatus(String message) {
                        emitStatus(context, listener, message);
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        emitProgress(context, listener, downloaded, total);
                    }
                });
        return result.profileId();
    }

    private void installFabricApi(DownloadTaskCenter.TaskContext context, String minecraftVersion,
                                  String profileId, Listener listener) throws Exception {
        requireActive(context);
        emitStatus(context, listener, "正在查找与 Minecraft " + minecraftVersion
                + " 匹配的 Fabric API…");
        ModProject project = ui.controller.modrinthApi().getProject(FABRIC_API_SLUG).get();
        ContentProject contentProject = new ContentProject(
                project.projectId(), project.slug(), project.title(), project.author(),
                project.description(), project.iconUrl() == null ? null : project.iconUrl().toString(),
                project.downloads(), project.follows(), "mod");
        File modsDirectory = ui.resolveModsDir(profileId);
        ui.controller.modrinthDownloader().downloadLatest(
                contentProject, minecraftVersion, "fabric", modsDirectory, true,
                new ModrinthDownloader.DownloadListener() {
                    @Override
                    public void onStatus(String message) {
                        emitStatus(context, listener, "Fabric API · " + message);
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        emitProgress(context, listener, downloaded, total);
                    }
                }, ".jar");
        emitStatus(context, listener, "Fabric API 已自动安装到当前实例。");
    }

    private void emitStatus(DownloadTaskCenter.TaskContext context,
                            Listener listener, String message) {
        context.updateStatus(message);
        Platform.runLater(() -> listener.onStatus(message));
    }

    private void emitProgress(DownloadTaskCenter.TaskContext context, Listener listener,
                              long downloaded, long total) {
        context.updateProgress(downloaded, total);
        Platform.runLater(() -> {
            listener.onProgress(downloaded, total);
            ui.updateProgress(ui.downloadProgress, downloaded, total);
        });
    }

    private static void requireActive(DownloadTaskCenter.TaskContext context) {
        if (context.isCancelled()) {
            throw new java.util.concurrent.CancellationException("实例安装已取消");
        }
    }
}
