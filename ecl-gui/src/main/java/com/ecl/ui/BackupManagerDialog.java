package com.ecl.ui;

import com.ecl.backup.BackupEntry;
import com.ecl.backup.WorldBackupService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns backup creation, restore and deletion UI for one instance. */
final class BackupManagerDialog {
    private final LauncherUI ui;

    BackupManagerDialog(LauncherUI ui) {
        this.ui = ui;
    }

    void show() {
        String profileId = ui.getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            ui.setStatus("无法打开备份管理", "请先选择一个实例。");
            return;
        }
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("备份管理 · " + profileId);
        ui.applyWindowIcon(dialog);

        ListView<BackupEntry> backupList = new ListView<>();
        backupList.setPrefHeight(270);
        backupList.setPlaceholder(ui.createBodyText(
                "还没有备份。点击“新建备份”保存当前存档。"));
        backupList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(BackupEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault()).format(item.createdAt());
                setText(created + "    " + formatBytes(item.archiveSize()) + "\n"
                        + formatContents(item) + " · " + item.files().size() + " 个文件");
                setTooltip(new Tooltip(item.archivePath().toString()));
            }
        });

        CheckBox includeSaves = new CheckBox("存档 saves（必选）");
        includeSaves.setSelected(true);
        includeSaves.setDisable(true);
        CheckBox includeMods = new CheckBox("模组 mods");
        includeMods.setSelected(ui.backupIncludeMods);
        CheckBox includeShaders = new CheckBox("光影包 shaderpacks");
        CheckBox includeResources = new CheckBox("材质包 resourcepacks");
        CheckBox includeConfig = new CheckBox("配置 config");
        HBox backupOptions = new HBox(18, includeSaves, includeMods, includeShaders,
                includeResources, includeConfig);
        backupOptions.setAlignment(Pos.CENTER_LEFT);

        Label operationStatus = ui.createBodyText("备份保存在 "
                + ui.worldBackupService.backupDirectory(profileId));
        ProgressBar operationProgress = new ProgressBar(0);
        operationProgress.setMaxWidth(Double.MAX_VALUE);
        Button createButton = button("新建备份", "primary-button");
        Button restoreButton = button("恢复所选", "secondary-button");
        restoreButton.setDisable(true);
        Button deleteButton = button("删除所选", "secondary-button");
        deleteButton.setDisable(true);
        Button openDirectoryButton = button("打开备份目录", "ghost-button");
        Button closeButton = button("关闭", "ghost-button");
        closeButton.setOnAction(event -> dialog.close());
        openDirectoryButton.setOnAction(event -> ui.openLocalFolder(
                ui.worldBackupService.backupDirectory(profileId).toFile(), "备份目录"));

        AtomicBoolean operationRunning = new AtomicBoolean();
        java.util.function.Consumer<Boolean> setBusy = busy -> {
            operationRunning.set(busy);
            createButton.setDisable(busy);
            openDirectoryButton.setDisable(busy);
            closeButton.setDisable(busy);
            boolean noSelection = backupList.getSelectionModel().getSelectedItem() == null;
            restoreButton.setDisable(busy || noSelection);
            deleteButton.setDisable(busy || noSelection);
            backupOptions.setDisable(busy);
            if (!busy) {
                operationProgress.setProgress(0);
            }
        };
        Runnable refreshList = () -> {
            try {
                BackupEntry selected = backupList.getSelectionModel().getSelectedItem();
                backupList.getItems().setAll(ui.worldBackupService.listBackups(profileId));
                if (selected != null) {
                    backupList.getItems().stream()
                            .filter(item -> item.archivePath().equals(selected.archivePath()))
                            .findFirst().ifPresent(item -> backupList.getSelectionModel().select(item));
                }
            } catch (IOException error) {
                operationStatus.setText("读取备份失败：" + cleanMessage(error));
            }
        };
        backupList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (!operationRunning.get()) {
                        restoreButton.setDisable(newValue == null);
                        deleteButton.setDisable(newValue == null);
                    }
                });

        createButton.setOnAction(event -> createBackup(profileId, backupList, includeMods,
                includeShaders, includeResources, includeConfig, operationStatus,
                operationProgress, backupOptions, restoreButton, deleteButton,
                openDirectoryButton, closeButton, setBusy, refreshList));
        restoreButton.setOnAction(event -> restoreBackup(profileId, dialog, backupList,
                operationStatus, operationProgress, setBusy, refreshList));
        deleteButton.setOnAction(event -> deleteBackup(dialog, backupList, operationStatus,
                setBusy, refreshList));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionBar = new HBox(10, createButton, restoreButton, deleteButton,
                spacer, openDirectoryButton, closeButton);
        actionBar.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(16,
                ui.createSurface("// " + profileId + " 的存档备份",
                        "恢复时会先校验压缩包，并为当前文件创建临时回滚副本。", backupList),
                ui.createSurface("新建备份包含内容", "saves 始终包含，其余目录按需选择",
                        backupOptions), operationStatus, operationProgress, actionBar);
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(20));
        Scene scene = new Scene(root, 920, 650);
        var stylesheet = BackupManagerDialog.class.getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        ui.applyThemeToScene(scene, ui.settingsManager.get(com.ecl.ECLConfig.KEY_THEME));
        refreshList.run();
        dialog.show();
    }

    private void createBackup(String profileId, ListView<BackupEntry> backupList,
                              CheckBox includeMods, CheckBox includeShaders,
                              CheckBox includeResources, CheckBox includeConfig,
                              Label status, ProgressBar progress, HBox options,
                              Button restore, Button delete, Button open, Button close,
                              java.util.function.Consumer<Boolean> setBusy,
                              Runnable refreshList) {
        Path instanceDirectory = ui.resolveVersionGameDir(profileId).toPath();
        if (!Files.isDirectory(instanceDirectory)) {
            status.setText("实例目录不存在，无法创建备份：" + instanceDirectory);
            return;
        }
        EnumSet<BackupEntry.Content> content = EnumSet.of(BackupEntry.Content.SAVES);
        if (includeMods.isSelected()) content.add(BackupEntry.Content.MODS);
        if (includeShaders.isSelected()) content.add(BackupEntry.Content.SHADERPACKS);
        if (includeResources.isSelected()) content.add(BackupEntry.Content.RESOURCEPACKS);
        if (includeConfig.isSelected()) content.add(BackupEntry.Content.CONFIG);
        setBusy.accept(true);
        status.setText("正在创建备份…");
        progress.setProgress(-1);
        WorldBackupService.ProgressListener listener = progressListener(progress, status, "正在备份");
        ui.runAsync("ecl-world-backup-create", () -> {
            try {
                BackupEntry created = ui.worldBackupService.createBackup(profileId,
                        resolveSourceVersion(profileId), instanceDirectory, content, listener);
                Platform.runLater(() -> {
                    refreshList.run();
                    backupList.getItems().stream()
                            .filter(item -> item.archivePath().equals(created.archivePath()))
                            .findFirst().ifPresent(item -> backupList.getSelectionModel().select(item));
                    status.setText("备份已创建：" + created.archivePath().getFileName());
                    ui.setStatus("实例备份完成", profileId + " · " + formatBytes(created.archiveSize()));
                    setBusy.accept(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    status.setText("备份失败：" + cleanMessage(error));
                    setBusy.accept(false);
                });
            }
        });
    }

    private void restoreBackup(String profileId, Stage dialog, ListView<BackupEntry> list,
                               Label status, ProgressBar progress,
                               java.util.function.Consumer<Boolean> setBusy,
                               Runnable refreshList) {
        BackupEntry selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (ui.gameLaunch.isGameProcessRunning()) {
            status.setText("游戏正在运行，退出游戏后才能恢复存档。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将用所选备份替换实例中的“" + formatContents(selected)
                        + "”。恢复过程会保留可回滚副本，确认继续吗？",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(dialog);
        confirm.setTitle("恢复实例备份");
        confirm.setHeaderText("恢复 " + selected.archivePath().getFileName());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        setBusy.accept(true);
        status.setText("正在校验并恢复备份…");
        progress.setProgress(-1);
        ui.runAsync("ecl-world-backup-restore", () -> {
            try {
                if (ui.gameLaunch.isGameProcessRunning()) {
                    throw new IOException("游戏正在运行，不能恢复实例文件");
                }
                ui.worldBackupService.restore(selected,
                        ui.resolveVersionGameDir(profileId).toPath(),
                        progressListener(progress, status, "正在恢复"));
                Platform.runLater(() -> {
                    status.setText("恢复完成：" + selected.archivePath().getFileName());
                    ui.setStatus("实例备份已恢复", profileId + " 的存档与所选内容已恢复。");
                    setBusy.accept(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    status.setText("恢复失败，原文件已回滚：" + cleanMessage(error));
                    setBusy.accept(false);
                });
            }
        });
    }

    private void deleteBackup(Stage dialog, ListView<BackupEntry> list, Label status,
                              java.util.function.Consumer<Boolean> setBusy,
                              Runnable refreshList) {
        BackupEntry selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将永久删除备份文件及其元数据。", ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(dialog);
        confirm.setTitle("删除实例备份");
        confirm.setHeaderText(selected.archivePath().getFileName().toString());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        setBusy.accept(true);
        status.setText("正在删除备份…");
        ui.runAsync("ecl-world-backup-delete", () -> {
            try {
                ui.worldBackupService.deleteBackup(selected);
                Platform.runLater(() -> {
                    refreshList.run();
                    status.setText("备份已删除。");
                    setBusy.accept(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    status.setText("删除失败：" + cleanMessage(error));
                    setBusy.accept(false);
                });
            }
        });
    }

    private WorldBackupService.ProgressListener progressListener(
            ProgressBar progress, Label status, String action) {
        AtomicLong lastUpdate = new AtomicLong();
        return (completed, total, entry) -> {
            long now = System.nanoTime();
            long previous = lastUpdate.get();
            if (completed < total && previous != 0 && now - previous < 80_000_000L) return;
            if (!lastUpdate.compareAndSet(previous, now) && completed < total) return;
            Platform.runLater(() -> {
                progress.setProgress(total <= 0 ? -1
                        : Math.max(0, Math.min(1, completed / (double) total)));
                String current = entry == null || entry.isBlank()
                        ? "" : " · " + abbreviate(entry, 64);
                status.setText(action + " " + formatBytes(completed)
                        + (total > 0 ? " / " + formatBytes(total) : "") + current);
            });
        };
    }

    private String resolveSourceVersion(String profileId) {
        try {
            return ui.versionManager.resolveMinecraftVersionId(profileId);
        } catch (IOException ignored) {
            return profileId;
        }
    }

    private static Button button(String text, String style) {
        return LauncherUiFactory.actionButton(text, style, () -> { });
    }

    private static String formatContents(BackupEntry backup) {
        StringBuilder result = new StringBuilder();
        for (BackupEntry.Content content : BackupEntry.Content.values()) {
            if (!backup.includedContent().contains(content)) continue;
            if (!result.isEmpty()) result.append("、");
            result.append(content.displayName());
        }
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String cleanMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getMessage() == null || cursor.getMessage().isBlank()
                ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
