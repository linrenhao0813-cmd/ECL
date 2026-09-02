package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.launch.GameProcess;
import com.ecl.launch.GameProcessMarker;
import com.ecl.util.InstanceOperationLease;
import com.ecl.launcher.CrashAnalyzer;
import javafx.application.Platform;

import java.io.File;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Monitors the game process and reports output, crashes, and lifecycle completion. */
final class GameProcessMonitor {
    private final LauncherUI ui;
    private final Consumer<String> outputConsumer;
    private final BiConsumer<CrashAnalyzer.Report, File> errorConsumer;
    private final SessionRecorder sessionRecorder;

    @FunctionalInterface
    interface SessionRecorder {
        void record(String version, long launchStartedAt, long elapsedNanos);
    }

    GameProcessMonitor(LauncherUI ui, Consumer<String> outputConsumer,
                       BiConsumer<CrashAnalyzer.Report, File> errorConsumer, SessionRecorder sessionRecorder) {
        this.ui = ui;
        this.outputConsumer = outputConsumer;
        this.errorConsumer = errorConsumer;
        this.sessionRecorder = sessionRecorder;
    }

    void monitor(GameProcess gameProcess, String version, File launchDir,
                 long launchStartedAt, long launchStartedNanos,
                 UUID runningInstanceId, boolean restoreLauncher,
                 InstanceOperationLease launchLock) {
        // 守护线程：关闭启动器窗口后进程能立即退出，不会被该监控线程拖住；
        // 游戏本体是独立进程，启动器退出不影响其继续运行。
        Thread.ofPlatform().name("ecl-monitor-game-" + version).daemon(true).start(() -> {
            LauncherLogBuffer output = new LauncherLogBuffer(ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS);
            com.ecl.launch.ProcessOutputListener outputListener = line -> {
                output.appendLine(line);
                outputConsumer.accept(line);
            };
            gameProcess.attachOutputListener(outputListener);
            var process = gameProcess.process();
            try {
                int exitCode = process.waitFor();
                gameProcess.outputPump().awaitStopped(5, java.util.concurrent.TimeUnit.SECONDS);
                if (exitCode == 0) {
                    runOnUiIfActive(() -> ui.setStatus("游戏已正常退出", version + " 退出码 0。"));
                    return;
                }

                CrashAnalyzer.Report report = CrashAnalyzer.analyzeGameExit(
                        version, exitCode, gameProcess.capturedOutput(), launchDir, launchStartedAt);
                runOnUiIfActive(() -> {
                    ui.setStatus("游戏异常退出", report.getTitle());
                    errorConsumer.accept(report, launchDir);
                });
            } catch (Exception error) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(
                    version, error, launchDir);
                runOnUiIfActive(() -> {
                    ui.setStatus("错误分析失败", report.getTitle());
                    errorConsumer.accept(report, launchDir);
                });
            } finally {
                gameProcess.detachOutputListener(outputListener);
                sessionRecorder.record(version, launchStartedAt, System.nanoTime() - launchStartedNanos);
                if (runningInstanceId != null) {
                    ui.controller.setInstanceRunning(runningInstanceId, false);
                }
                ui.unregisterActiveGameProcess(process);
                try {
                    GameProcessMarker.clear(launchDir.toPath(), process.toHandle());
                } catch (java.io.IOException error) {
                    LauncherUI.LOGGER.warn("Failed to clear game process marker for {}", version,
                            error);
                }
                if (launchLock != null) {
                    try {
                        launchLock.close();
                    } catch (java.io.IOException error) {
                        LauncherUI.LOGGER.warn("Failed to release game launch lock for {}", version,
                                error);
                    }
                }
                runOnUiIfActive(() -> {
                    ui.updateRuntimeSummary();
                    if (restoreLauncher) {
                        ui.primaryStage.setIconified(false);
                        ui.primaryStage.show();
                        ui.primaryStage.toFront();
                    }
                });
            }
        });
    }

    private void runOnUiIfActive(Runnable action) {
        if (ui.applicationStopping.get()) {
            return;
        }
        Platform.runLater(() -> {
            if (!ui.applicationStopping.get()) {
                action.run();
            }
        });
    }
}
