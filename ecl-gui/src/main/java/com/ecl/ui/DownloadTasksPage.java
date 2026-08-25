package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.util.Messages;
import com.ecl.util.HttpUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/** Download center page, task rendering and bandwidth controls. */
final class DownloadTasksPage extends VBox {
    private static final double PAGE_WIDTH = 1180;

    private final DownloadTaskCenter taskCenter;
    private final SettingsManager settingsManager;
    private final ListView<DownloadTaskCenter.TaskSnapshot> taskList = new ListView<>();
    private final Label summaryLabel = LauncherUiFactory.bodyText(
            Messages.get("download.summary.none"));

    DownloadTasksPage(DownloadTaskCenter taskCenter, SettingsManager settingsManager) {
        this.taskCenter = taskCenter;
        this.settingsManager = settingsManager;
        getStyleClass().add("launch-pane");
        setSpacing(18);
        setPrefWidth(PAGE_WIDTH);
        setMaxWidth(PAGE_WIDTH);
        HBox.setHgrow(this, Priority.ALWAYS);
        build();
    }

    void updateTasks(List<DownloadTaskCenter.TaskSnapshot> tasks) {
        long active = tasks.stream().filter(task ->
                task.status() == DownloadTaskCenter.Status.QUEUED
                        || task.status() == DownloadTaskCenter.Status.RUNNING
                        || task.status() == DownloadTaskCenter.Status.CANCELLING).count();
        long failed = tasks.stream().filter(task ->
                task.status() == DownloadTaskCenter.Status.FAILED).count();
        taskList.getItems().setAll(tasks);
        summaryLabel.setText(active == 0
                ? (failed == 0 ? Messages.get("download.summary.none")
                : Messages.format("download.summary.failed", failed))
                : Messages.format("download.summary.active", active));
    }

    private void build() {
        taskList.setPrefHeight(430);
        taskList.setPlaceholder(LauncherUiFactory.bodyText(Messages.get("download.placeholder")));
        taskList.setCellFactory(list -> new TaskCell());
        taskList.getItems().setAll(taskCenter.snapshots());

        ComboBox<Integer> concurrency = new ComboBox<>();
        concurrency.getItems().addAll(1, 2, 3, 4, 5, 6, 7, 8);
        concurrency.setValue(taskCenter.maxConcurrent());
        concurrency.setOnAction(event -> {
            Integer value = concurrency.getValue();
            if (value == null) {
                return;
            }
            taskCenter.setMaxConcurrent(value);
            HttpUtil.setDownloadMaxConcurrent(value);
            settingsManager.set(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, value);
            settingsManager.save();
        });

        ComboBox<String> rate = new ComboBox<>();
        rate.getItems().addAll(Messages.get("download.rate.unlimited"),
                "256 KB/s", "512 KB/s", "1 MB/s", "2 MB/s", "4 MB/s", "8 MB/s");
        rate.setValue(downloadRateLabel(taskCenter.bandwidthLimitBytesPerSecond()));
        rate.setOnAction(event -> {
            long bytes = parseDownloadRate(rate.getValue());
            taskCenter.setBandwidthLimitBytesPerSecond(bytes);
            HttpUtil.setDownloadRateLimitBytesPerSecond(bytes);
            settingsManager.set(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB,
                    (int) (bytes / 1024));
            settingsManager.save();
        });
        Button clear = LauncherUiFactory.actionButton(
                Messages.get("download.action.clear"), "ghost-button",
                taskCenter::clearFinished);
        HBox controls = new HBox(12,
                LauncherUiFactory.controlRow(
                        Messages.get("download.settings.concurrency"), concurrency),
                LauncherUiFactory.controlRow(
                        Messages.get("download.settings.speedLimit"), rate), clear);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(controls.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(controls.getChildren().get(1), Priority.ALWAYS);
        getChildren().addAll(
                LauncherUiFactory.surface(Messages.get("download.center.title"),
                        Messages.get("download.center.subtitle"), summaryLabel, controls),
                LauncherUiFactory.surface(Messages.get("download.tasks.title"),
                        Messages.get("download.tasks.subtitle"), taskList));
    }

    private String downloadStatusText(DownloadTaskCenter.TaskSnapshot task) {
        return switch (task.status()) {
            case QUEUED -> Messages.get("download.status.queued");
            case RUNNING -> Messages.get("download.status.running");
            case CANCELLING -> Messages.get("download.status.cancelling");
            case COMPLETED -> Messages.get("download.status.completed");
            case FAILED -> Messages.format("download.status.failed", task.errorMessage());
            case CANCELLED -> Messages.get("download.status.cancelled");
        };
    }

    private String downloadTaskMeta(DownloadTaskCenter.TaskSnapshot task) {
        String transfer = task.totalBytes() > 0
                ? formatBytes(task.downloadedBytes()) + " / " + formatBytes(task.totalBytes())
                : formatBytes(task.downloadedBytes());
        String speed = task.speedBytesPerSecond() > 0
                ? " · " + formatBytes(task.speedBytesPerSecond()) + "/s" : "";
        return Messages.format("download.meta", transfer, speed, task.attempts());
    }

    private String downloadRateLabel(long bytes) {
        if (bytes <= 0) {
            return Messages.get("download.rate.unlimited");
        }
        if (bytes % (1024L * 1024L) == 0) {
            return (bytes / (1024L * 1024L)) + " MB/s";
        }
        return (bytes / 1024L) + " KB/s";
    }

    private long parseDownloadRate(String value) {
        if (value == null || value.equals(Messages.get("download.rate.unlimited"))) {
            return 0;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }
        long amount = Long.parseLong(digits);
        return value.contains("MB") ? amount * 1024L * 1024L : amount * 1024L;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private final class TaskCell extends ListCell<DownloadTaskCenter.TaskSnapshot> {
        @Override
        protected void updateItem(DownloadTaskCenter.TaskSnapshot item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label title = new Label(item.title());
            title.getStyleClass().add("content-title");
            Label status = new Label(downloadStatusText(item));
            status.getStyleClass().add("status-detail");
            Label detail = new Label(item.detail());
            detail.getStyleClass().add("content-subtitle");
            detail.setWrapText(true);
            ProgressBar progress = new ProgressBar(item.progress());
            progress.setPrefWidth(210);
            progress.setVisible(item.status() == DownloadTaskCenter.Status.QUEUED
                    || item.status() == DownloadTaskCenter.Status.RUNNING
                    || item.status() == DownloadTaskCenter.Status.CANCELLING);
            Label meta = new Label(downloadTaskMeta(item));
            meta.getStyleClass().add("content-subtitle");
            VBox text = new VBox(3, title, status, detail, meta);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button action = actionFor(item);
            HBox row = new HBox(12, text, progress);
            if (action != null) {
                row.getChildren().add(action);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 4, 8, 4));
            setGraphic(row);
        }

        private Button actionFor(DownloadTaskCenter.TaskSnapshot item) {
            if (item.status() == DownloadTaskCenter.Status.QUEUED
                    || item.status() == DownloadTaskCenter.Status.RUNNING) {
                return LauncherUiFactory.actionButton(
                        Messages.get("download.action.cancel"), "ghost-button",
                        () -> taskCenter.cancel(item.id()));
            }
            if (item.status() == DownloadTaskCenter.Status.FAILED
                    || item.status() == DownloadTaskCenter.Status.CANCELLED) {
                return LauncherUiFactory.actionButton(
                        Messages.get("download.action.retry"), "secondary-button",
                        () -> taskCenter.retry(item.id()));
            }
            return null;
        }
    }
}
