package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.service.InstanceUpgradeRecoveryService;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shows and restores the last update snapshot for one explicit instance. */
final class InstanceUpgradeRecoveryWorkflow {
    private final LauncherUI ui;
    private final AtomicBoolean busy = new AtomicBoolean();

    InstanceUpgradeRecoveryWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    boolean isBusy() {
        return busy.get();
    }

    void open(String profileId, Runnable onFinished) {
        if (profileId == null || profileId.isBlank() || !busy.compareAndSet(false, true)) return;
        if (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)) {
            busy.set(false);
            finish(profileId, Messages.get("instanceRecovery.launchPreparing"), onFinished);
            return;
        }
        Path versions = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        Path gameRoot = ui.getConfiguredGameRootDir().toPath().toAbsolutePath().normalize();
        ModInstanceContext instance;
        try {
            instance = VersionProfileModInstanceContext.load(profileId, versions, gameRoot,
                    ui.gameRepository().runDirectory(profileId));
        } catch (IOException | IllegalArgumentException error) {
            finish(profileId, Messages.format("instanceRecovery.failed", ui.cleanMessage(error)), onFinished);
            return;
        }
        ui.controller.registerModInstance(instance);
        DownloadTaskCenter.TaskHandle<InstanceUpgradeRecoveryService.RestorePoint> task;
        try {
            task = ui.downloadTaskCenter.submit(Messages.format("instanceRecovery.inspectTask", profileId), context -> {
                context.updateStatus(Messages.get("instanceRecovery.inspecting"));
                if (context.isCancelled()) throw new CancellationException("Recovery inspection cancelled");
                if (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)) {
                    throw new IOException(Messages.get("instanceRecovery.launchPreparing"));
                }
                return ui.controller.instanceUpgradeRecovery().inspect(profileId, instance, versions, gameRoot);
            });
        } catch (RuntimeException rejected) {
            finish(profileId, Messages.format("instanceRecovery.failed", ui.cleanMessage(rejected)), onFinished);
            return;
        }
        task.completion().whenComplete((point, error) -> Platform.runLater(() -> {
            if (error != null) {
                finish(profileId, Messages.format("instanceRecovery.failed", ui.cleanMessage(unwrap(error))),
                        onFinished);
            } else if (point == null || point.files().isEmpty()) {
                finish(profileId, Messages.get("instanceRecovery.noPoint"), onFinished);
            } else if ("RESTORED".equals(point.state())) {
                finish(profileId, Messages.get("instanceRecovery.alreadyRestored"), onFinished);
            } else {
                confirmRestore(profileId, instance, versions, gameRoot, point, onFinished);
            }
        }));
    }

    private void confirmRestore(String profileId, ModInstanceContext instance, Path versions,
                                Path gameRoot, InstanceUpgradeRecoveryService.RestorePoint point,
                                Runnable onFinished) {
        boolean interrupted = point.restorationPending();
        List<String> conflicts = point.conflicts();
        String body = formatPoint(point, conflicts);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, body,
                new ButtonType(Messages.get(interrupted ? "instanceRecovery.resume" : "instanceRecovery.restore")),
                ButtonType.CANCEL);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("instanceRecovery.title"));
        alert.setHeaderText(Messages.format("instanceRecovery.header", profileId));
        ButtonType choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) {
            finish(profileId, Messages.get("instanceRecovery.cancelled"), onFinished);
            return;
        }
        if (!conflicts.isEmpty() && !interrupted) {
            confirmConflicts(profileId, instance, versions, gameRoot, point, onFinished);
        } else {
            startRestore(profileId, instance, versions, gameRoot, Set.of(), onFinished);
        }
    }

    private void confirmConflicts(String profileId, ModInstanceContext instance, Path versions,
                                  Path gameRoot, InstanceUpgradeRecoveryService.RestorePoint point,
                                  Runnable onFinished) {
        TextArea paths = new TextArea(String.join("\n", point.conflicts()));
        paths.setEditable(false);
        paths.setWrapText(true);
        paths.setPrefRowCount(Math.min(16, point.conflicts().size()));
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.get("instanceRecovery.conflictBody"),
                new ButtonType(Messages.get("instanceRecovery.overwrite")), ButtonType.CANCEL);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("instanceRecovery.conflictTitle"));
        alert.setHeaderText(Messages.get("instanceRecovery.conflictHeader"));
        alert.getDialogPane().setContent(new javafx.scene.layout.VBox(8,
                new javafx.scene.control.Label(Messages.get("instanceRecovery.conflictFiles")), paths));
        if (alert.showAndWait().orElse(ButtonType.CANCEL).getText()
                .equals(Messages.get("instanceRecovery.overwrite"))) {
            startRestore(profileId, instance, versions, gameRoot,
                    new LinkedHashSet<>(point.conflicts()), onFinished);
        } else {
            finish(profileId, Messages.get("instanceRecovery.cancelled"), onFinished);
        }
    }

    private String formatPoint(InstanceUpgradeRecoveryService.RestorePoint point,
                               List<String> conflicts) {
        StringBuilder text = new StringBuilder(Messages.format("instanceRecovery.point",
                point.createdAt(), Messages.get("instanceRecovery.state."
                        + point.state().toLowerCase(java.util.Locale.ROOT)), point.files().size(), point.pack()
                        ? Messages.get("instanceRecovery.pack")
                        : Messages.get("instanceRecovery.modsAndLoader")));
        text.append("\n\n").append(Messages.get("instanceRecovery.files"));
        point.files().stream().limit(40).forEach(path -> text.append("\n• ").append(path));
        if (point.files().size() > 40) {
            text.append("\n").append(Messages.format("instanceRecovery.more", point.files().size() - 40));
        }
        if (!conflicts.isEmpty()) {
            text.append("\n\n").append(Messages.format("instanceRecovery.conflicts", conflicts.size()));
        }
        if (point.restorationPending()) {
            text.append("\n\n").append(Messages.get("instanceRecovery.pending"));
        }
        return text.toString();
    }

    private void startRestore(String profileId, ModInstanceContext instance, Path versions,
                              Path gameRoot, Set<String> confirmedConflicts,
                              Runnable onFinished) {
        DownloadTaskCenter.TaskHandle<InstanceUpgradeRecoveryService.RestoreResult> task;
        try {
            task = ui.downloadTaskCenter.submit(Messages.format("instanceRecovery.restoreTask", profileId), context -> {
                context.updateStatus(Messages.get("instanceRecovery.restoring"));
                if (context.isCancelled()) throw new CancellationException("Rollback cancelled");
                if (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)) {
                    throw new IOException(Messages.get("instanceRecovery.launchPreparing"));
                }
                return ui.controller.instanceUpgradeRecovery().restore(profileId, instance, versions,
                        gameRoot, confirmedConflicts);
            });
        } catch (RuntimeException rejected) {
            finish(profileId, Messages.format("instanceRecovery.failed", ui.cleanMessage(rejected)), onFinished);
            return;
        }
        task.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                finish(profileId, Messages.format("instanceRecovery.failed", ui.cleanMessage(unwrap(error))),
                        onFinished);
            } else if (!result.restored() && !result.conflicts().isEmpty()) {
                InstanceUpgradeRecoveryService.RestorePoint refreshed = new InstanceUpgradeRecoveryService.RestorePoint(
                        profileId, java.time.Instant.parse(result.createdAt()), "ACTIVE", List.of(),
                        result.conflicts(), false, false);
                confirmConflicts(profileId, instance, versions, gameRoot, refreshed, onFinished);
            } else if (result.restored()) {
                ui.controller.invalidateLaunchVersion(profileId);
                ui.versionManager.invalidateLocalVersionProfiles();
                ui.updateRuntimeSummary();
                finish(profileId, Messages.get("instanceRecovery.restored"), onFinished);
            } else {
                finish(profileId, Messages.get("instanceRecovery.cancelled"), onFinished);
            }
        }));
    }

    private void finish(String profileId, String message, Runnable onFinished) {
        busy.set(false);
        ui.setStatus(Messages.get("instanceRecovery.title"), message);
        onFinished.run();
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("instanceRecovery.title"));
        alert.setHeaderText(profileId);
        alert.showAndWait();
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
}
