package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.download.GameDownloader;
import javafx.application.Platform;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Validates, persists, and downloads the selected instance before handing off to process launch. */
final class GameLaunchPreparation {
    private final LauncherUI ui;
    private final LaunchUiFacade facade;

    GameLaunchPreparation(LauncherUI ui, LaunchUiFacade facade) {
        this.ui = ui;
        this.facade = facade;
    }

    void prepareAndLaunch(Consumer<String> launcher, Runnable retry) {
        String selectedVersion = facade.selectedVersion();
        if (selectedVersion == null || selectedVersion.isBlank()) {
            ui.setStatus("请选择游戏版本", "先刷新并选择一个可启动的 Minecraft 版本。 ");
            return;
        }
        LoaderChoice requestedLoader = facade.requestedLoader();
        if (requestedLoader != null && !requestedLoader.vanilla()
                && facade.loaderForProfile(selectedVersion) != requestedLoader) {
            facade.installSelectedLoader(retry);
            return;
        }
        if (facade.lastContentVersion() != null && !facade.lastContentVersion().equals(selectedVersion)
                && facade.isVersionDownloaded(facade.lastContentVersion())) {
            ui.setStatus("注意：启动版本与已下载内容的版本不一致", "模组 / 光影 / 材质包已下载到 "
                    + facade.lastContentVersion() + " 的实例目录，当前将启动 " + selectedVersion
                    + "，这些内容不会被加载。可切换到 " + ui.lastContentVersion + " 再启动。 ");
        }
        String configuredJavaPath = ui.javaPath == null ? "" : ui.javaPath.trim();
        boolean profileNeedsMigration = !java.nio.file.Files.isRegularFile(ui.controller.instanceLaunchProfiles()
                .profileFile(ui.resolveVersionInstanceRoot(selectedVersion).toPath()));
        if (profileNeedsMigration && !configuredJavaPath.isBlank()
                && !com.ecl.util.JavaRuntimeUtil.isUsableJavaPath(configuredJavaPath)) {
            ui.setStatus("Java 路径无效", "高级设置里的 Java 路径不可用，请重新选择 java.exe 或 JDK 根目录。 ");
            return;
        }
        if (configuredJavaPath.isBlank()) ui.javaPath = "";
        ui.settingsManager.set(ECLConfig.KEY_JAVA_PATH, ui.javaPath);
        ui.settingsManager.set(ECLConfig.KEY_GAME_DIR, ui.gameDir.getAbsolutePath());
        ui.settingsManager.set(ECLConfig.KEY_JVM_ARGS, ui.extraJvmArgs == null ? "" : ui.extraJvmArgs);
        ui.settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, ui.maxMemoryMb);
        ui.settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, selectedVersion);
        ui.settingsManager.set(ECLConfig.KEY_AUTH_TYPE, ui.authTypeCombo.getValue());
        ui.settingsManager.set(ECLConfig.KEY_USERNAME, ui.usernameField.getText().trim());
        if (LauncherUI.AUTH_YGGDRASIL.equals(ui.authTypeCombo.getValue()))
            ui.settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, ui.yggdrasilServerField.getText().trim());
        ui.runAsync("ecl-save-settings", () -> {
            if (!ui.settingsManager.save()) Platform.runLater(() -> ui.setStatus("设置保存失败",
                    "无法写入 settings.json，请检查目录权限或查看日志。"));
        });
        ui.updateRuntimeSummary();
        CompletableFuture<Boolean> readiness =
                ui.versionManager.ensureVersionDownloadedAsync(selectedVersion);
        if (readiness.isDone() && !readiness.isCompletedExceptionally()) {
            if (Boolean.TRUE.equals(readiness.getNow(false))) launcher.accept(selectedVersion);
            else downloadAndLaunch(selectedVersion, launcher);
            return;
        }
        ui.setControlsBusy(true);
        ui.setStatus("正在验证本地版本", selectedVersion + " 的旧安装正在后台执行完整性检查。");
        readiness.whenComplete((ready, error) -> Platform.runLater(() -> {
            if (!Objects.equals(selectedVersion, facade.selectedVersion())) {
                ui.setControlsBusy(false);
                return;
            }
            ui.setControlsBusy(false);
            ui.updateRuntimeSummary();
            if (error != null) {
                ui.setStatus("本地版本检查失败", ui.cleanMessage(error));
            } else if (Boolean.TRUE.equals(ready)) {
                launcher.accept(selectedVersion);
            } else {
                downloadAndLaunch(selectedVersion, launcher);
            }
        }));
    }

    private void downloadAndLaunch(String version, Consumer<String> launcher) {
        com.ecl.launcher.VersionManager.VersionDownloadTarget target;
        try { target = ui.versionManager.resolveDownloadTarget(version); }
        catch (IOException error) { ui.setStatus("无法解析基础版本", ui.cleanMessage(error)); return; }
        String downloadVersion = target.downloadVersionId();
        String url = target.versionUrl();
        if (url == null || url.isBlank()) {
            ui.setStatus("找不到基础版本下载地址", version + " 需要 " + downloadVersion
                    + "，但当前 Mojang 版本清单中没有该版本。请刷新版本列表后重试。");
            return;
        }
        ui.setControlsBusy(true);
        ui.downloadProgress.setProgress(0);
        ui.startProgressAnimation(ui.downloadProgress);
        ui.setStatus("正在准备下载", version.equals(downloadVersion)
                ? version + " 首次启动需要补齐客户端、依赖库和资源文件。"
                : version + " 将继承 " + downloadVersion + "，正在补齐基础客户端、依赖库和资源文件。");
        var task = ui.downloadTaskCenter.submit("Minecraft " + downloadVersion, () -> context -> {
            AtomicReference<String> failure = new AtomicReference<>();
            ui.downloader.setListener(new GameDownloader.DownloadListener() {
                @Override public void onStatus(String message) { context.updateStatus(message); Platform.runLater(() -> ui.setStatus("下载中", message)); }
                @Override public void onProgress(long downloaded, long total) {
                    context.updateProgress(downloaded, total); Platform.runLater(() -> {
                        ui.updateProgress(ui.downloadProgress, downloaded, total);
                        ui.detailLabel.setText("当前进度: " + ui.formatBytes(downloaded)
                                + (total > 0 ? " / " + ui.formatBytes(total) : ""));
                    });
                }
                @Override public void onError(String message) { failure.set(message); Platform.runLater(() -> {
                    if (context.isCancelled()) return; ui.setStatus("下载失败", message);
                    ui.stopProgressAnimation(ui.downloadProgress, true); ui.setControlsBusy(false);
                }); }
                @Override public void onComplete() { Platform.runLater(() -> {
                    if (context.isCancelled()) return;
                    ui.downloadProgress.setProgress(1); ui.stopProgressAnimation(ui.downloadProgress, true);
                    if (!ui.versionManager.isVersionDownloaded(version)) { ui.setStatus("基础版本仍不完整",
                            downloadVersion + " 下载完成，但 " + version + " 的继承客户端仍不可用，请检查版本配置。");
                        ui.setControlsBusy(false); return; }
                    ui.setStatus("下载完成", downloadVersion + " 已就绪，准备启动 " + version + "。");
                    try { ui.gameRepository().applyDefaultIsolationSettingForNewInstance(version); }
                    catch (IOException error) { LauncherUI.LOGGER.warn("Cannot persist default isolation for {}", version, error); }
                    launcher.accept(version);
                }); }
            });
            context.registerCancellation(ui.downloader::cancelDownload);
            Future<?> future = ui.downloader.downloadVersionAsync(
                    downloadVersion, url, target.versionSha1()); future.get();
            if (failure.get() != null && !failure.get().isBlank()) throw new IOException(failure.get());
            return null;
        });
        task.completion().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error == null) return;
            ui.stopProgressAnimation(ui.downloadProgress, true);
            ui.setStatus(ui.isCancellation(error) ? com.ecl.util.Messages.get("download.status.cancelled")
                    : com.ecl.util.Messages.get("download.status.failedTitle"), ui.cleanMessage(error));
            ui.setControlsBusy(false);
        }));
    }
}
