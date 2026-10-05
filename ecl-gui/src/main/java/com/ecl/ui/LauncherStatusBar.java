package com.ecl.ui;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom status bar shared by every page. It owns the live task summary, the shared download
 * progress bar and an on-demand task panel listing every queued, running and finished download
 * task with its target, stage, result and the actions that are actually available.
 */
final class LauncherStatusBar {
    private static final int MAX_VISIBLE_TASKS = 60;

    private final LauncherUI ui;
    private final VBox dock = new VBox();
    private final VBox detailPanel = new VBox(8);
    private final ListView<DownloadTaskCenter.TaskSnapshot> taskList = new ListView<>();
    private boolean detailOpen;
    private int activeTaskCount;

    LauncherStatusBar(LauncherUI ui) {
        this.ui = ui;
    }

    VBox create() {
        dock.setId("task-dock");
        dock.getStyleClass().add("task-dock");

        ui.statusLabel = new Label(Messages.get("home.noTasks"));
        ui.statusLabel.setId("status-summary");
        ui.statusLabel.getStyleClass().add("status-title");

        ui.detailLabel = new Label(Messages.get("home.taskDetail"));
        ui.detailLabel.setId("task-detail-text");
        ui.detailLabel.getStyleClass().add("status-detail");
        ui.detailLabel.setWrapText(true);
        ui.detailLabel.setMaxWidth(Double.MAX_VALUE);

        ui.downloadProgress = new ProgressBar(0);
        ui.downloadProgress.setId("shared-download-progress");
        ui.downloadProgress.getStyleClass().addAll("download-progress", "status-progress");
        ui.downloadProgress.setPrefWidth(180);
        ui.downloadProgress.setMaxWidth(180);
        LauncherUiFactory.setVisible(ui.downloadProgress, false);

        ui.taskToggleButton = new Button(Messages.get("tasks.entry"));
        ui.taskToggleButton.setId("task-entry-button");
        ui.taskToggleButton.getStyleClass().addAll("app-button", "status-task-button");
        ui.taskToggleButton.setAccessibleText(Messages.get("tasks.entry"));
        ui.taskToggleButton.setOnAction(event -> toggleDetail());

        detailPanel.setId("task-detail-panel");
        detailPanel.getStyleClass().add("task-detail-panel");
        detailPanel.setMaxWidth(Double.MAX_VALUE);
        detailPanel.setVisible(false);
        detailPanel.setManaged(false);

        taskList.setId("task-list");
        taskList.getStyleClass().add("task-list");
        taskList.setPlaceholder(new Label(Messages.get("tasks.empty")));
        taskList.setCellFactory(view -> new TaskCell());
        taskList.setPrefHeight(180);
        taskList.setMinHeight(120);
        taskList.setMaxHeight(220);
        detailPanel.getChildren().addAll(ui.detailLabel, taskList);

        Label version = new Label("ECL " + Messages.get("app.version"));
        version.setId("status-version");
        version.getStyleClass().add("status-version");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, version, ui.statusLabel, spacer,
                ui.downloadProgress, ui.taskToggleButton);
        bar.setId("status-bar");
        bar.getStyleClass().add("window-status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        dock.getChildren().addAll(detailPanel, bar);

        if (ui.downloadTaskCenter != null) {
            ui.downloadTaskCenter.addListener(this::onTasksChanged);
            onTasksChanged(ui.downloadTaskCenter.snapshots());
        }
        return dock;
    }

    /** Reapplies locale-dependent task entry text without disturbing the live status summary. */
    void refreshTexts() {
        if (ui.taskToggleButton == null) {
            return;
        }
        applyTaskCount();
        if (ui.downloadTaskCenter != null) {
            onTasksChanged(ui.downloadTaskCenter.snapshots());
        }
    }

    /** Restores the localized idle summary after a locale change while nothing is running. */
    void resetToIdle() {
        if (activeTaskCount > 0 || ui.statusLabel == null) {
            return;
        }
        ui.statusLabel.setText(Messages.get("status.ready"));
        ui.detailLabel.setText(Messages.get("status.ready.detail"));
    }

    /** Opens the task panel, used by the home page when the launch target is already running. */
    void showDetail() {
        if (!detailOpen) {
            toggleDetail();
        }
    }

    private void toggleDetail() {
        detailOpen = !detailOpen;
        detailPanel.setVisible(detailOpen);
        detailPanel.setManaged(detailOpen);
        ui.taskToggleButton.setText(detailOpen
                ? Messages.get("tasks.entry.hide") : Messages.get("tasks.entry"));
    }

    private void onTasksChanged(List<DownloadTaskCenter.TaskSnapshot> tasks) {
        List<DownloadTaskCenter.TaskSnapshot> ordered = new ArrayList<>();
        int active = 0;
        if (tasks != null) {
            for (DownloadTaskCenter.TaskSnapshot task : tasks) {
                if (isActive(task.status())) {
                    active++;
                }
            }
            // Newest first, and never let the panel grow without bound.
            for (int i = tasks.size() - 1; i >= 0 && ordered.size() < MAX_VISIBLE_TASKS; i--) {
                ordered.add(tasks.get(i));
            }
        }
        int previous = activeTaskCount;
        activeTaskCount = active;
        if (Platform.isFxApplicationThread()) {
            applyTasks(ordered, previous);
        } else {
            Platform.runLater(() -> applyTasks(ordered, previous));
        }
    }

    private void applyTasks(List<DownloadTaskCenter.TaskSnapshot> tasks, int previousActive) {
        if (ui.taskToggleButton == null) {
            return;
        }
        taskList.setItems(FXCollections.observableArrayList(tasks));
        applyTaskCount();
        if (activeTaskCount == 0 && previousActive > 0) {
            ui.stopProgressAnimation(ui.downloadProgress, true);
        }
    }

    private void applyTaskCount() {
        if (ui.taskToggleButton == null) {
            return;
        }
        if (activeTaskCount > 0) {
            ui.taskToggleButton.setText(Messages.format("tasks.entry.active", activeTaskCount));
        } else if (!detailOpen) {
            ui.taskToggleButton.setText(Messages.get("tasks.entry"));
        }
    }

    private static boolean isActive(DownloadTaskCenter.Status status) {
        return status == DownloadTaskCenter.Status.QUEUED
                || status == DownloadTaskCenter.Status.RUNNING
                || status == DownloadTaskCenter.Status.CANCELLING;
    }

    private static boolean canCancel(DownloadTaskCenter.Status status) {
        return status == DownloadTaskCenter.Status.QUEUED
                || status == DownloadTaskCenter.Status.RUNNING;
    }

    private static boolean canRetry(DownloadTaskCenter.Status status) {
        return status == DownloadTaskCenter.Status.FAILED
                || status == DownloadTaskCenter.Status.CANCELLED;
    }

    private static String statusText(DownloadTaskCenter.Status status) {
        return Messages.get("tasks.status." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** One task row: target, stage, progress, result and the actions that are actually available. */
    private final class TaskCell extends ListCell<DownloadTaskCenter.TaskSnapshot> {
        private final Label title = new Label();
        private final Label stage = new Label();
        private final Label state = new Label();
        private final ProgressBar progress = new ProgressBar(0);
        private final Button cancelButton = new Button(Messages.get("tasks.action.cancel"));
        private final Button retryButton = new Button(Messages.get("tasks.action.retry"));
        private final HBox row = new HBox(10);

        private TaskCell() {
            title.getStyleClass().add("task-item-title");
            stage.getStyleClass().add("task-item-stage");
            stage.setWrapText(true);
            state.getStyleClass().add("task-item-state");
            progress.getStyleClass().add("download-progress");
            progress.setPrefWidth(120);
            progress.setMaxWidth(120);
            progress.setMinHeight(6);
            progress.setMaxHeight(6);
            cancelButton.getStyleClass().addAll("app-button", "tiny-button", "ghost-button");
            retryButton.getStyleClass().addAll("app-button", "tiny-button", "secondary-button");
            cancelButton.setOnAction(event -> {
                DownloadTaskCenter.TaskSnapshot task = getItem();
                if (task != null && ui.downloadTaskCenter != null) {
                    ui.downloadTaskCenter.cancel(task.id());
                }
            });
            retryButton.setOnAction(event -> {
                DownloadTaskCenter.TaskSnapshot task = getItem();
                if (task != null && ui.downloadTaskCenter != null) {
                    ui.downloadTaskCenter.retry(task.id());
                }
            });
            VBox text = new VBox(2, title, stage);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(text, state, progress, cancelButton, retryButton);
        }

        @Override
        protected void updateItem(DownloadTaskCenter.TaskSnapshot task, boolean empty) {
            super.updateItem(task, empty);
            if (empty || task == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            title.setText(task.title());
            String detail = task.detail() == null ? "" : task.detail();
            if (!task.errorMessage().isBlank()) {
                detail = detail.isBlank() ? task.errorMessage() : detail + " · " + task.errorMessage();
            }
            stage.setText(detail.isBlank() ? statusText(task.status()) : detail);
            state.setText(statusText(task.status()));
            applyStateStyle(state, task.status());
            progress.setProgress(task.progress());
            LauncherUiFactory.setVisible(progress, isActive(task.status()));
            boolean cancellable = canCancel(task.status());
            cancelButton.setManaged(cancellable);
            cancelButton.setVisible(cancellable);
            retryButton.setManaged(canRetry(task.status()));
            retryButton.setVisible(canRetry(task.status()));
            cancelButton.setAccessibleText(Messages.get("tasks.action.cancel") + " " + task.title());
            retryButton.setAccessibleText(Messages.get("tasks.action.retry") + " " + task.title());
            setText(null);
            setGraphic(row);
        }

        private void applyStateStyle(Label label, DownloadTaskCenter.Status status) {
            label.getStyleClass().removeAll("task-state-active", "task-state-done",
                    "task-state-failed", "task-state-idle");
            label.getStyleClass().add(switch (status) {
                case RUNNING, QUEUED, CANCELLING -> "task-state-active";
                case COMPLETED -> "task-state-done";
                case FAILED -> "task-state-failed";
                case CANCELLED -> "task-state-idle";
            });
        }
    }
}
