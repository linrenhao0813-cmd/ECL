package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** Owns the loader install form and its cancellable download task. */
final class LoaderInstallDialog {
    private final LauncherUI ui;

    LoaderInstallDialog(LauncherUI ui) {
        this.ui = ui;
    }

    void show() {
        String selected = ui.getSelectedVersion();
        String minecraftVersion = selected;
        if (selected != null && !selected.isBlank()) {
            try {
                minecraftVersion = ui.versionManager.resolveMinecraftVersionId(selected);
            } catch (java.io.IOException ignored) {
                minecraftVersion = selected;
            }
        }
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("安装 Mod 加载器");
        ui.applyWindowIcon(dialog);

        TextField minecraftField = new TextField(minecraftVersion == null ? "" : minecraftVersion);
        minecraftField.setPromptText("例如 1.21.4");
        ui.applyFieldStyle(minecraftField);
        ComboBox<String> loaderField = new ComboBox<>();
        loaderField.getItems().setAll("Fabric", "Quilt", "Forge", "NeoForge");
        loaderField.getSelectionModel().selectFirst();
        ui.applyFieldStyle(loaderField);
        TextField versionField = new TextField();
        versionField.setPromptText("留空自动选择最新兼容版本");
        ui.applyFieldStyle(versionField);
        Label installStatus = ui.createBodyText(
                "Fabric / Quilt 使用官方 profile；Forge / NeoForge 会运行官方 installer。");
        Button install = LauncherUiFactory.actionButton("安装", "primary-button", () -> { });
        Button cancel = LauncherUiFactory.actionButton("取消", "ghost-button", dialog::close);
        install.setOnAction(event -> {
            String gameVersion = minecraftField.getText().trim();
            if (gameVersion.isBlank()) {
                installStatus.setText("请填写 Minecraft 版本。");
                return;
            }
            ModLoaderInstaller.Loader loader = switch (loaderField.getValue()) {
                case "Quilt" -> ModLoaderInstaller.Loader.QUILT;
                case "Forge" -> ModLoaderInstaller.Loader.FORGE;
                case "NeoForge" -> ModLoaderInstaller.Loader.NEOFORGE;
                default -> ModLoaderInstaller.Loader.FABRIC;
            };
            install.setDisable(true);
            cancel.setDisable(true);
            ui.setControlsBusy(true);
            ui.startProgressAnimation(ui.downloadProgress);
            ui.downloadTaskCenter.submit("Loader " + loader.displayName(), context -> {
                try {
                    ModLoaderInstaller.InstallResult result = ui.modLoaderInstaller.install(
                            gameVersion, loader, versionField.getText().trim(),
                            new ModLoaderInstaller.Listener() {
                                @Override
                                public void onStatus(String message) {
                                    context.updateStatus(message);
                                    Platform.runLater(() -> {
                                        installStatus.setText(message);
                                        ui.setStatus("正在安装加载器", message);
                                    });
                                }

                                @Override
                                public void onProgress(long downloaded, long total) {
                                    context.updateProgress(downloaded, total);
                                    Platform.runLater(() -> ui.updateProgress(
                                            ui.downloadProgress, downloaded, total));
                                }
                            });
                    ui.gameRepository().applyDefaultIsolationSettingForNewInstance(result.profileId());
                    if (context.isCancelled()) return null;
                    Platform.runLater(() -> {
                        if (context.isCancelled()) return;
                        ui.stopProgressAnimation(ui.downloadProgress, true);
                        ui.setControlsBusy(false);
                        ui.versionActions.restoreVersionComboItems(result.profileId());
                        ui.versionCombo.setValue(result.profileId());
                        ui.setStatus("加载器安装完成", result.loader().displayName() + " "
                                + result.loaderVersion() + " / Minecraft " + result.minecraftVersion());
                        dialog.close();
                        ui.renderActiveView();
                    });
                } catch (Exception error) {
                    boolean cancelled = context.isCancelled() || ui.isCancellation(error);
                    Platform.runLater(() -> {
                        ui.stopProgressAnimation(ui.downloadProgress, true);
                        ui.setControlsBusy(false);
                        install.setDisable(false);
                        cancel.setDisable(false);
                        if (cancelled) {
                            installStatus.setText(Messages.get("download.status.cancelled"));
                            ui.setStatus(Messages.get("download.status.cancelled"), "");
                        } else {
                            String message = ui.cleanMessage(error);
                            installStatus.setText(Messages.format(
                                    "download.status.failed", message));
                            ui.setStatus(Messages.get("status.downloadFailed"), message);
                        }
                    });
                    throw error;
                }
                return null;
            });
        });

        HBox buttons = new HBox(10, install, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(14,
                ui.createSurface("Minecraft 版本", "加载器必须与游戏版本严格匹配", minecraftField),
                ui.createSurface("加载器", null, loaderField),
                ui.createSurface("加载器版本", "通常留空即可", versionField),
                installStatus, buttons);
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(20));
        Scene scene = new Scene(root, 560, 470);
        var stylesheet = LoaderInstallDialog.class.getResource("/css/launcher.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        dialog.setScene(scene);
        ui.applyThemeToScene(scene, ui.settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
    }
}
