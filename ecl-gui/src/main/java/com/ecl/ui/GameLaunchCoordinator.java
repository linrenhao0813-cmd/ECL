package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineSkin;
import com.ecl.auth.OfflineSkinStore;
import com.ecl.backup.BackupEntry;
import com.ecl.download.GameDownloader;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.game.PlaytimeTracker;
import com.ecl.launcher.CrashAnalyzer;
import com.ecl.launch.GameProcess;
import com.ecl.launch.LaunchOptions;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Owns game launch, download-and-launch, process monitoring, console and crash handling. */
final class GameLaunchCoordinator {
    private final LauncherUI ui;
    private final LaunchUiFacade facade;
    private final LaunchAuthFactory authFactory;
    private final GameProcessMonitor processMonitor;

    GameLaunchCoordinator(LauncherUI ui) {
        this(ui, new LauncherUiFacadeAdapter(ui));
    }

    GameLaunchCoordinator(LauncherUI ui, LaunchUiFacade facade) {
        this.ui = ui;
        this.facade = facade;
        this.authFactory = new LaunchAuthFactory(ui);
        this.processMonitor = new GameProcessMonitor(ui, this::appendGameConsoleLine,
                this::showGameErrorDialog);
    }

    void launchGame() {
        String selectedVersion = facade.selectedVersion();
        if (selectedVersion == null || selectedVersion.isBlank()) {
            ui.setStatus("请选择游戏版本", "先刷新并选择一个可启动的 Minecraft 版本。 ");
            return;
        }
        LoaderChoice requestedLoader = facade.requestedLoader();
        if (requestedLoader != null && !requestedLoader.vanilla()
                && facade.loaderForProfile(selectedVersion) != requestedLoader) {
            facade.installSelectedLoader(this::launchGame);
            return;
        }

        if (facade.lastContentVersion() != null && !facade.lastContentVersion().equals(selectedVersion)
                && facade.isVersionDownloaded(facade.lastContentVersion())) {
            ui.setStatus("注意：启动版本与已下载内容的版本不一致",
                    "模组 / 光影 / 材质包已下载到 " + facade.lastContentVersion()
                            + " 的实例目录，当前将启动 " + selectedVersion
                            + "，这些内容不会被加载。可切换到 " + ui.lastContentVersion + " 再启动。 ");
        }

        String configuredJavaPath = ui.javaPath == null ? "" : ui.javaPath.trim();
        boolean profileNeedsMigration = !java.nio.file.Files.isRegularFile(
                ui.controller.instanceLaunchProfiles()
                        .profileFile(ui.resolveVersionInstanceRoot(selectedVersion).toPath()));
        if (profileNeedsMigration && !configuredJavaPath.isBlank()
                && !com.ecl.util.JavaRuntimeUtil.isUsableJavaPath(configuredJavaPath)) {
            ui.setStatus("Java 路径无效", "高级设置里的 Java 路径不可用，请重新选择 java.exe 或 JDK 根目录。 ");
            return;
        }

        if (configuredJavaPath.isBlank()) {
            ui.javaPath = "";
        }

        ui.settingsManager.set(ECLConfig.KEY_JAVA_PATH, ui.javaPath);
        ui.settingsManager.set(ECLConfig.KEY_GAME_DIR, ui.gameDir.getAbsolutePath());
        ui.settingsManager.set(ECLConfig.KEY_JVM_ARGS, ui.extraJvmArgs == null ? "" : ui.extraJvmArgs);
        ui.settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, ui.maxMemoryMb);
        ui.settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, selectedVersion);
        ui.settingsManager.set(ECLConfig.KEY_AUTH_TYPE, ui.authTypeCombo.getValue());
        ui.settingsManager.set(ECLConfig.KEY_USERNAME, ui.usernameField.getText().trim());
        if (LauncherUI.AUTH_YGGDRASIL.equals(ui.authTypeCombo.getValue())) {
            ui.settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, ui.yggdrasilServerField.getText().trim());
        }
        // 启动参数先在内存中快照，再在后台线程持久化，避免启动路径阻塞 UI 线程写盘。
        ui.runAsync("ecl-save-settings", () -> {
            if (!ui.settingsManager.save()) {
                Platform.runLater(() -> ui.setStatus("设置保存失败",
                        "无法写入 settings.json，请检查目录权限或查看日志。"));
            }
        });
        ui.updateRuntimeSummary();

        if (!ui.versionManager.isVersionDownloaded(selectedVersion)) {
            downloadAndLaunch(selectedVersion);
        } else {
            startGame(selectedVersion);
        }
    }

    private void downloadAndLaunch(String version) {
        com.ecl.launcher.VersionManager.VersionDownloadTarget downloadTarget;
        try {
            downloadTarget = ui.versionManager.resolveDownloadTarget(version);
        } catch (IOException error) {
            ui.setStatus("无法解析基础版本", ui.cleanMessage(error));
            return;
        }
        String downloadVersion = downloadTarget.downloadVersionId();
        String url = downloadTarget.versionUrl();
        if (url == null || url.isBlank()) {
            ui.setStatus("找不到基础版本下载地址",
                    version + " 需要 " + downloadVersion
                            + "，但当前 Mojang 版本清单中没有该版本。请刷新版本列表后重试。");
            return;
        }

        ui.setControlsBusy(true);
        ui.downloadProgress.setProgress(0);
        ui.startProgressAnimation(ui.downloadProgress);
        ui.setStatus("正在准备下载",
                version.equals(downloadVersion)
                        ? version + " 首次启动需要补齐客户端、依赖库和资源文件。"
                        : version + " 将继承 " + downloadVersion
                                + "，正在补齐基础客户端、依赖库和资源文件。");

        com.ecl.download.DownloadTaskCenter.TaskHandle<Void> task = ui.downloadTaskCenter.submit(
                "Minecraft " + downloadVersion, () -> context -> {
                    AtomicReference<String> downloadFailure = new AtomicReference<>();
                    ui.downloader.setListener(new GameDownloader.DownloadListener() {
                        @Override
                        public void onStatus(String message) {
                            context.updateStatus(message);
                            Platform.runLater(() -> ui.setStatus("下载中", message));
                        }

                        @Override
                        public void onProgress(long downloaded, long total) {
                            context.updateProgress(downloaded, total);
                            Platform.runLater(() -> {
                                ui.updateProgress(ui.downloadProgress, downloaded, total);
                                ui.detailLabel.setText("当前进度: " + ui.formatBytes(downloaded)
                                        + (total > 0 ? " / " + ui.formatBytes(total) : ""));
                            });
                        }

                        @Override
                        public void onError(String message) {
                            downloadFailure.set(message);
                            Platform.runLater(() -> {
                                if (context.isCancelled()) return;
                                ui.setStatus("下载失败", message);
                                ui.stopProgressAnimation(ui.downloadProgress, true);
                                ui.setControlsBusy(false);
                            });
                        }

                        @Override
                        public void onComplete() {
                            Platform.runLater(() -> {
                                if (context.isCancelled()) return;
                                ui.downloadProgress.setProgress(1);
                                ui.stopProgressAnimation(ui.downloadProgress, true);
                                if (!ui.versionManager.isVersionDownloaded(version)) {
                                    ui.setStatus("基础版本仍不完整",
                                            downloadVersion + " 下载完成，但 " + version
                                                    + " 的继承客户端仍不可用，请检查版本配置。");
                                    ui.setControlsBusy(false);
                                    return;
                                }
                                ui.setStatus("下载完成",
                                        downloadVersion + " 已就绪，准备启动 " + version + "。");
                                try {
                                    ui.gameRepository().applyDefaultIsolationSettingForNewInstance(version);
                                } catch (IOException error) {
                                    LauncherUI.LOGGER.warn("Cannot persist default isolation for {}", version, error);
                                }
                                startGame(version);
                            });
                        }
                    });
                    context.registerCancellation(ui.downloader::cancelDownload);
                    Future<?> future = ui.downloader.downloadVersionAsync(downloadVersion, url);
                    future.get();
                    String failure = downloadFailure.get();
                    if (failure != null && !failure.isBlank()) throw new IOException(failure);
                    return null;
                });
        task.completion().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error == null) return;
            ui.stopProgressAnimation(ui.downloadProgress, true);
            if (ui.isCancellation(error)) {
                ui.setStatus(com.ecl.util.Messages.get("download.status.cancelled"), ui.cleanMessage(error));
            } else {
                ui.setStatus(com.ecl.util.Messages.get("download.status.failedTitle"), ui.cleanMessage(error));
            }
            ui.setControlsBusy(false);
        }));
    }

    private void startGame(String version) {
        String authType = ui.authTypeCombo.getValue();
        String server = ui.yggdrasilServerField.getText().trim();
        String username = ui.usernameField.getText().trim();
        AtomicReference<String> passwordRef = new AtomicReference<>(ui.passwordField.getText());
        ui.passwordField.clear(); // 尽快清除 UI 中的密码，减少敏感数据驻留时间

        ui.setControlsBusy(true);
        ui.stopProgressAnimation(ui.downloadProgress, true);
        ui.setStatus("正在启动游戏...", "准备认证、拼接类路径并拉起客户端进程。 ");

        ui.runAsync("ecl-launch-game", () -> {
            File launchDir = ui.resolveVersionGameDir(version);
            File instanceRoot = ui.resolveVersionInstanceRoot(version);
            String password = passwordRef.getAndSet(null);
            try {
                ensureVersionGameDirs(version);
                InstanceLaunchProfile launchProfile = ui.controller.instanceLaunchProfiles()
                        .load(instanceRoot.toPath());
                String instanceJavaPath = launchProfile.javaMode() == InstanceLaunchProfile.JavaMode.AUTO
                        ? "" : launchProfile.javaPath();
                if (!instanceJavaPath.isBlank()
                        && !com.ecl.util.JavaRuntimeUtil.isUsableJavaPath(instanceJavaPath)) {
                    throw new IOException("Instance Java path is not usable: " + instanceJavaPath);
                }
                int instanceMemoryMb = launchProfile.memoryMode() == InstanceLaunchProfile.MemoryMode.AUTO
                        ? ECLConfig.calculateAutoMemoryMb() : launchProfile.maxMemoryMb();
                AuthProvider auth = authFactory.create(authType, server, username, password);
                password = null;
                OfflineSkin offlineSkin = auth.getType() == com.ecl.auth.AuthType.OFFLINE
                        ? new OfflineSkinStore()
                                .find(OfflineSkinStore.identityForOffline(auth.getUsername()))
                                .orElse(null)
                        : null;
                LaunchOptions options = LaunchOptions.builder()
                        .versionId(version)
                        .auth(auth)
                        .offlineSkin(offlineSkin)
                        .gameDirectory(launchDir)
                        .instanceDirectory(instanceRoot)
                        .environment(ui.controller.launchEnvironment())
                        .maxMemoryMb(instanceMemoryMb)
                        .jvmArguments(launchProfile.customJvmArguments())
                        .javaExecutablePath(instanceJavaPath)
                        .gameResolution(ui.gameWidth, ui.gameHeight)
                        .fullscreen(ui.gameFullscreen)
                        .serverAddress(ui.quickServer)
                        .processorCount(ui.processorCount)
                        .build();
                createAutomaticBackupBeforeLaunch(version, launchDir.toPath());
                ui.controller.invalidateLaunchVersion(version);
                GameProcess gameProcess = ui.gameLauncher.launch(options);
                Process process = gameProcess.process();
                long launchStartedAt = process.info().startInstant()
                        .map(Instant::toEpochMilli)
                        .orElseGet(System::currentTimeMillis);
                long launchStartedNanos = System.nanoTime();
                try {
                    ui.playtimeTracker.recordLaunch(ui.resolveVersionInstanceRoot(version).toPath(), launchStartedAt);
                } catch (IOException statsError) {
                    LauncherUI.LOGGER.warn("Cannot record launch statistics for {}", version, statsError);
                }
                ui.registerActiveGameProcess(process, version);
                UUID runningInstanceId = registerRunningModInstance(version);
                boolean minimizeThisLaunch = ui.closeAfterLaunch;

                runOnUiIfActive(() -> {
                    ui.setStatus("游戏已启动", version + " 正在运行，实例目录: " + launchDir.getAbsolutePath());
                    ui.updateRuntimeSummary();
                    ui.setControlsBusy(false);
                    if (ui.showGameConsole) {
                        ui.setActiveView(AppView.LOGS);
                    }
                    if (minimizeThisLaunch) {
                        ui.primaryStage.setIconified(true);
                    }
                });
                processMonitor.monitor(gameProcess, version, launchDir, launchStartedAt,
                        launchStartedNanos,
                        runningInstanceId, minimizeThisLaunch);
            } catch (Exception e) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, launchDir);
                runOnUiIfActive(() -> {
                    ui.setStatus("启动失败", report.getTitle());
                    showGameErrorDialog(report);
                    ui.setControlsBusy(false);
                });
            } finally {
                password = null;
                passwordRef.set(null);
            }
        });
    }

    private void createAutomaticBackupBeforeLaunch(String profileId, java.nio.file.Path instanceDirectory) {
        if (!ui.backupOnLaunch) return;
        EnumSet<BackupEntry.Content> content = EnumSet.of(BackupEntry.Content.SAVES);
        if (ui.backupIncludeMods) content.add(BackupEntry.Content.MODS);
        try {
            BackupEntry created = ui.worldBackupService.createBackup(profileId,
                    ui.resolveBackupSourceVersion(profileId), instanceDirectory, content, null);
            List<BackupEntry> removed = ui.worldBackupService.prune(profileId, ui.backupKeepCount);
            LauncherUI.LOGGER.info("Created pre-launch backup {} for {} ({} bytes); pruned {} old backups",
                    created.archivePath(), profileId, created.archiveSize(), removed.size());
        } catch (Exception error) {
            LauncherUI.LOGGER.warn("Pre-launch backup failed for {}; game launch will continue",
                    profileId, error);
        }
    }

    private UUID registerRunningModInstance(String version) {
        try {
            ModInstanceContext instance = VersionProfileModInstanceContext.load(
                    version,
                    ECLConfig.getVersionsDir().toPath(),
                    ui.getConfiguredGameRootDir().toPath(),
                    ui.resolveVersionGameDir(version).toPath());
            ui.controller.registerModInstance(instance);
            ui.controller.setInstanceRunning(instance.instanceId(), true);
            return instance.instanceId();
        } catch (Exception e) {
            LauncherUI.LOGGER.warn("Cannot register running mod instance for {}", version, e);
            return null;
        }
    }

    private void runOnUiIfActive(Runnable action) {
        if (ui.applicationStopping.get()) return;
        Platform.runLater(() -> {
            if (!ui.applicationStopping.get()) action.run();
        });
    }

    void appendGameConsoleLine(String line) {
        ui.liveGameLog.appendLine(line);
        if (ui.applicationStopping.get()) return;
        synchronized (ui.pendingConsoleText) {
            ui.pendingConsoleText.append(line).append(System.lineSeparator());
            int excess = ui.pendingConsoleText.length() - ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS;
            if (excess > 0) {
                ui.pendingConsoleText.delete(0, excess);
            }
        }
        if (ui.consoleFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushPendingConsoleText);
        }
    }

    private void flushPendingConsoleText() {
        String batch;
        synchronized (ui.pendingConsoleText) {
            batch = ui.pendingConsoleText.toString();
            ui.pendingConsoleText.setLength(0);
        }
        ui.consoleFlushScheduled.set(false);
        javafx.scene.control.TextArea area = ui.liveConsoleArea;
        if (area != null && !batch.isEmpty()) {
            area.appendText(batch);
            int excess = area.getLength() - ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS;
            if (excess > 0) area.deleteText(0, excess);
            area.positionCaret(area.getLength());
        }
        synchronized (ui.pendingConsoleText) {
            if (ui.pendingConsoleText.length() > 0
                    && ui.consoleFlushScheduled.compareAndSet(false, true)) {
                Platform.runLater(this::flushPendingConsoleText);
            }
        }
    }

    private void showGameErrorDialog(CrashAnalyzer.Report report) {
        CrashDiagnosticDialog.show(ui.primaryStage, report, ui.resolveModsDir(ui.getSelectedVersion()),
                folder -> ui.openLocalFolder(folder, "诊断目录"));
    }

    boolean isGameProcessRunning() {
        return ui.hasRunningGameProcess();
    }

    void updatePlaytimeSummary() {
        if (ui.playtimeTotalLabel == null || ui.versionCombo == null) return;
        String selected = ui.versionCombo.getValue();
        if (selected == null || selected.isBlank()) {
            ui.playtimeTotalLabel.setText(com.ecl.util.Messages.get("label.notSelected"));
            ui.playtimeRecentLabel.setText(com.ecl.util.Messages.get("playtime.never"));
            ui.playtimeLaunchCountLabel.setText("0");
            return;
        }
        try {
            PlaytimeTracker.PlaytimeStats stats = ui.playtimeTracker.stats(
                    ui.resolveVersionInstanceRoot(selected).toPath());
            ui.playtimeTotalLabel.setText(formatPlaytime(stats.totalSeconds()));
            ui.playtimeRecentLabel.setText(stats.lastLaunchedAt().isBlank() ? com.ecl.util.Messages.get("playtime.never")
                    : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.parse(stats.lastLaunchedAt())));
            ui.playtimeLaunchCountLabel.setText(String.valueOf(stats.launchCount()));
        } catch (IOException | RuntimeException error) {
            ui.playtimeTotalLabel.setText(com.ecl.util.Messages.get("playtime.unavailable"));
            ui.playtimeRecentLabel.setText("-");
            ui.playtimeLaunchCountLabel.setText("-");
        }
    }

    private String formatPlaytime(long seconds) {
        long hours = Math.max(0, seconds) / 3600;
        long minutes = (Math.max(0, seconds) % 3600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    int getEffectiveMaxMemoryMb() {
        return ui.maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? ECLConfig.calculateAutoMemoryMb()
                : ui.maxMemoryMb;
    }

    private void ensureVersionGameDirs(String gameVersion) throws IOException {
        File instanceDir = ui.resolveVersionGameDir(gameVersion);
        ui.ensureDirectory(instanceDir);
        ui.ensureDirectory(new File(instanceDir, "mods"));
        ui.ensureDirectory(new File(instanceDir, "shaderpacks"));
        ui.ensureDirectory(new File(instanceDir, "resourcepacks"));
        ui.ensureDirectory(new File(instanceDir, "saves"));
        ui.ensureDirectory(new File(instanceDir, "logs"));
    }

    String getMemoryDisplayText() {
        int effectiveMemoryMb = getEffectiveMaxMemoryMb();
        return ui.maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? "自动 " + effectiveMemoryMb + " MB"
                : effectiveMemoryMb + " MB";
    }
}
