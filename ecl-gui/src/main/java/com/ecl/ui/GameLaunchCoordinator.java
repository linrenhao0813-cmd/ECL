package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineSkin;
import com.ecl.auth.OfflineSkinStore;
import com.ecl.backup.BackupEntry;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.launcher.CrashAnalyzer;
import com.ecl.launch.GameProcess;
import com.ecl.launch.GameProcessMarker;
import com.ecl.launch.LaunchOptions;
import com.ecl.util.InstanceOperationLease;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Owns game launch, download-and-launch, process monitoring, console and crash handling. */
final class GameLaunchCoordinator {
    private final LauncherUI ui;
    private final LaunchUiFacade facade;
    private final LaunchAuthFactory authFactory;
    private final GameProcessMonitor processMonitor;
    private final GameLaunchPreparation preparation;
    private final GameConsoleController console;
    private final GamePlaytimeService playtime;

    GameLaunchCoordinator(LauncherUI ui) {
        this(ui, new LauncherUiFacadeAdapter(ui));
    }

    GameLaunchCoordinator(LauncherUI ui, LaunchUiFacade facade) {
        this.ui = ui;
        this.facade = facade;
        this.authFactory = new LaunchAuthFactory(ui);
        this.console = new GameConsoleController(ui);
        this.playtime = new GamePlaytimeService(ui);
        this.preparation = new GameLaunchPreparation(ui, facade);
        this.processMonitor = new GameProcessMonitor(ui, console::appendLine,
                this::showGameErrorDialog, playtime::recordSession);
    }

    void launchGame() {
        String selectedVersion = facade.selectedVersion();
        if (selectedVersion != null && ui.isVersionRunning(selectedVersion)) {
            ui.setStatus("游戏已在运行", selectedVersion + " 已经启动，请先退出当前游戏。");
            return;
        }
        preparation.prepareAndLaunch(this::startGame, this::launchGame);
    }

    private void startGame(String version) {
        String authType = ui.authTypeCombo.getValue();
        String server = ui.yggdrasilServerField.getText().trim();
        String username = ui.usernameField.getText().trim();
        AtomicReference<char[]> passwordRef = new AtomicReference<>(
                ui.passwordField.getText().toCharArray());
        ui.passwordField.clear(); // 尽快清除 UI 中的密码，减少敏感数据驻留时间

        ui.setControlsBusy(true);
        ui.stopProgressAnimation(ui.downloadProgress, true);
        ui.setStatus("正在启动游戏...", "准备认证、拼接类路径并拉起客户端进程。 ");

        ui.runAsync("ecl-launch-game", () -> {
            File launchDir = ui.resolveVersionGameDir(version);
            File instanceRoot = ui.resolveVersionInstanceRoot(version);
            char[] password = passwordRef.getAndSet(null);
            InstanceOperationLease gameLock = null;
            boolean monitorOwnsGameLock = false;
            try {
                ensureVersionGameDirs(version);
                createAutomaticBackupBeforeLaunch(version, launchDir.toPath());
                if (GameProcessMarker.isRunning(launchDir.toPath())) {
                    throw new IOException("实例中的游戏进程仍在运行: " + version);
                }
                gameLock = InstanceOperationLease.tryAcquire(launchDir.toPath());
                if (gameLock == null) {
                    throw new IOException("实例正在运行或被另一个启动器进程占用: " + version);
                }
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
                Arrays.fill(password, '\0');
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
                ui.controller.invalidateLaunchVersion(version);
                GameProcess gameProcess = ui.gameLauncher.launch(options);
                Process process = gameProcess.process();
                try {
                    GameProcessMarker.record(launchDir.toPath(), process.toHandle());
                } catch (IOException markerError) {
                    gameProcess.close();
                    throw new IOException("无法建立游戏进程运行标记，已停止本次启动", markerError);
                }
                long launchStartedAt = process.info().startInstant()
                        .map(Instant::toEpochMilli)
                        .orElseGet(System::currentTimeMillis);
                long launchStartedNanos = System.nanoTime();
                playtime.recordLaunch(version, launchStartedAt);
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
                        runningInstanceId, minimizeThisLaunch, gameLock);
                monitorOwnsGameLock = true;
            } catch (Exception e) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, launchDir);
                runOnUiIfActive(() -> {
                    ui.setStatus("启动失败", report.getTitle());
                    showGameErrorDialog(report, launchDir);
                    ui.setControlsBusy(false);
                });
            } finally {
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
                char[] queuedPassword = passwordRef.getAndSet(null);
                if (queuedPassword != null) {
                    Arrays.fill(queuedPassword, '\0');
                }
                if (!monitorOwnsGameLock && gameLock != null) {
                    try {
                        gameLock.close();
                    } catch (IOException lockError) {
                        LauncherUI.LOGGER.warn("Failed to release game launch lock for {}", version,
                                lockError);
                    }
                }
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

    private void showGameErrorDialog(CrashAnalyzer.Report report, File launchDir) {
        CrashDiagnosticDialog.show(ui.primaryStage, report, new File(launchDir, "mods"),
                folder -> ui.openLocalFolder(folder, "诊断目录"));
    }

    boolean isGameProcessRunning() {
        return ui.hasRunningGameProcess();
    }

    RuntimeSummary runtimeSummary(String version) {
        int memoryMb = ui.maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? ECLConfig.calculateAutoMemoryMb() : ui.maxMemoryMb;
        boolean autoMemory = ui.maxMemoryMb == ECLConfig.AUTO_MEMORY_MB;
        String javaPath = ui.javaPath == null ? "" : ui.javaPath;
        String jvmArguments = ui.extraJvmArgs == null ? "" : ui.extraJvmArgs;
        boolean customJava = !javaPath.isBlank();
        if (version != null && !version.isBlank()) {
            try {
                java.nio.file.Path profileRoot = ui.resolveVersionInstanceRoot(version).toPath();
                java.nio.file.Path profileFile = ui.controller.instanceLaunchProfiles()
                        .profileFile(profileRoot);
                if (java.nio.file.Files.isRegularFile(profileFile)) {
                    InstanceLaunchProfile profile = ui.controller.instanceLaunchProfiles().load(profileRoot);
                    memoryMb = profile.memoryMode() == InstanceLaunchProfile.MemoryMode.AUTO
                            ? ECLConfig.calculateAutoMemoryMb() : profile.maxMemoryMb();
                    autoMemory = profile.memoryMode() == InstanceLaunchProfile.MemoryMode.AUTO;
                    customJava = profile.javaMode() == InstanceLaunchProfile.JavaMode.CUSTOM;
                    javaPath = profile.javaPath();
                    jvmArguments = String.join(" ", profile.customJvmArguments());
                }
            } catch (IOException | RuntimeException error) {
                LauncherUI.LOGGER.warn("Cannot read instance runtime summary for {}", version, error);
            }
        }
        return new RuntimeSummary(
                customJava ? "实例 Java 自定义" : "实例 Java 自动",
                customJava ? javaPath : "",
                memoryMb,
                autoMemory,
                jvmArguments);
    }

    void updatePlaytimeSummary() {
        playtime.updateSummary();
    }

    int getEffectiveMaxMemoryMb() {
        return runtimeSummary(ui.getSelectedVersion()).memoryMb();
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
        RuntimeSummary summary = runtimeSummary(ui.getSelectedVersion());
        return summary.autoMemory()
                ? "自动 " + summary.memoryMb() + " MB"
                : summary.memoryMb() + " MB";
    }

    record RuntimeSummary(String javaText, String javaPath, int memoryMb, boolean autoMemory,
                          String jvmArguments) {
        String memoryText() {
            return autoMemory ? "自动 " + memoryMb + " MB" : memoryMb + " MB";
        }
    }
}
