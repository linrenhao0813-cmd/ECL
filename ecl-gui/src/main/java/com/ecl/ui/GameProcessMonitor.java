package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.launch.GameProcess;
import com.ecl.launcher.CrashAnalyzer;
import javafx.application.Platform;

import java.io.File;
import java.util.UUID;
import java.util.function.Consumer;

/** Monitors the game process and reports output, crashes, and lifecycle completion. */
final class GameProcessMonitor {
    private final LauncherUI ui;
    private final Consumer<String> outputConsumer;
    private final Consumer<CrashAnalyzer.Report> errorConsumer;
    private final SessionRecorder sessionRecorder;

    @FunctionalInterface
    interface SessionRecorder {
        void record(String version, long launchStartedAt, long elapsedNanos);
    }

    GameProcessMonitor(LauncherUI ui, Consumer<String> outputConsumer,
                       Consumer<CrashAnalyzer.Report> errorConsumer, SessionRecorder sessionRecorder) {
        this.ui = ui;
        this.outputConsumer = outputConsumer;
        this.errorConsumer = errorConsumer;
        this.sessionRecorder = sessionRecorder;
    }

    void monitor(GameProcess gameProcess, String version, File launchDir,
                 long launchStartedAt, long launchStartedNanos,
                 UUID runningInstanceId, boolean restoreLauncher) {
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
                if (exitCode == 0) {
                    runOnUiIfActive(() -> ui.setStatus("游戏已正常退出", version + " 退出码 0。"));
                    return;
                }

                CrashAnalyzer.Report report = CrashAnalyzer.analyzeGameExit(
                        version, exitCode, output.toString(), launchDir, launchStartedAt);
                runOnUiIfActive(() -> {
                    ui.setStatus("游戏异常退出", report.getTitle());
                    errorConsumer.accept(report);
                });
            } catch (Exception error) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(
                        version, error, launchDir);
                runOnUiIfActive(() -> {
                    ui.setStatus("错误分析失败", report.getTitle());
                    errorConsumer.accept(report);
                });
            } finally {
                gameProcess.detachOutputListener(outputListener);
                sessionRecorder.record(version, launchStartedAt, System.nanoTime() - launchStartedNanos);
                if (runningInstanceId != null) {
                    ui.controller.setInstanceRunning(runningInstanceId, false);
                }
                ui.unregisterActiveGameProcess(process);
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
