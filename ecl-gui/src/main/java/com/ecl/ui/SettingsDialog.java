package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.util.Messages;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.io.File;
import java.net.URL;

/** Edits global game behavior without writing instance profiles or new-instance defaults. */
final class SettingsDialog {
    private final LauncherUI ui;

    SettingsDialog(LauncherUI ui) {
        this.ui = ui;
    }

    void show() {
        Scene ownerScene = ui.primaryStage.getScene();
        javafx.scene.Node focusReturnTarget = ownerScene == null ? null : ownerScene.getFocusOwner();
        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(Messages.get("settings.advanced"));
        ui.applyWindowIcon(dialog);

        TextField dirField = new TextField(ui.gameDir.getAbsolutePath());
        dirField.setId("settings-global-game-dir");
        dirField.setPromptText(Messages.get("label.gameDir"));
        ui.applyFieldStyle(dirField);

        Button dirBrowseBtn = new Button(Messages.get("settings.browse"));
        dirBrowseBtn.getStyleClass().addAll("app-button", "secondary-button");
        dirBrowseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(Messages.get("label.gameDir"));
            File initial = ui.prepareChooserDir(dirField.getText());
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            File selected = chooser.showDialog(dialog);
            if (selected != null) {
                dirField.setText(selected.getAbsolutePath());
            }
        });

        HBox dirBox = new HBox(10, dirField, dirBrowseBtn);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        TextField widthField = new TextField(Integer.toString(ui.gameWidth));
        widthField.setId("settings-global-width");
        widthField.setPromptText(Messages.get("settings.global.width"));
        ui.applyFieldStyle(widthField);
        TextField heightField = new TextField(Integer.toString(ui.gameHeight));
        heightField.setPromptText(Messages.get("settings.global.height"));
        ui.applyFieldStyle(heightField);
        HBox resolutionBox = new HBox(10, widthField, new Label("×"), heightField);
        resolutionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(widthField, Priority.ALWAYS);
        HBox.setHgrow(heightField, Priority.ALWAYS);

        CheckBox fullscreenField = new CheckBox(Messages.get("settings.global.fullscreen"));
        fullscreenField.setSelected(ui.gameFullscreen);
        TextField serverField = new TextField(ui.quickServer == null ? "" : ui.quickServer);
        serverField.setPromptText(Messages.get("settings.global.serverHint"));
        ui.applyFieldStyle(serverField);
        TextField processorField = new TextField(ui.processorCount <= 0 ? "" : Integer.toString(ui.processorCount));
        processorField.setPromptText(Messages.get("settings.global.processorHint"));
        ui.applyFieldStyle(processorField);
        CheckBox closeAfterLaunchField = new CheckBox(Messages.get("settings.global.hide"));
        closeAfterLaunchField.setSelected(ui.closeAfterLaunch);
        VBox behaviorBox = new VBox(10, fullscreenField, closeAfterLaunchField);

        CheckBox backupOnLaunchField = new CheckBox(Messages.get("settings.global.backup"));
        backupOnLaunchField.setSelected(ui.backupOnLaunch);
        TextField backupKeepCountField = new TextField(Integer.toString(ui.backupKeepCount));
        backupKeepCountField.setPromptText(Messages.get("settings.global.keepHint"));
        backupKeepCountField.setMaxWidth(160);
        ui.applyFieldStyle(backupKeepCountField);
        CheckBox backupIncludeModsField = new CheckBox(Messages.get("settings.global.backupMods"));
        backupIncludeModsField.setSelected(ui.backupIncludeMods);
        Label backupKeepCountLabel = new Label(Messages.get("settings.global.keep"));
        backupKeepCountLabel.getStyleClass().add("info-key");
        Label backupKeepCountUnit = new Label(Messages.get("settings.global.copies"));
        HBox backupKeepCountBox = new HBox(10, backupKeepCountLabel,
                backupKeepCountField, backupKeepCountUnit);
        backupKeepCountBox.setAlignment(Pos.CENTER_LEFT);
        VBox backupBehaviorBox = new VBox(10, backupOnLaunchField,
                backupKeepCountBox, backupIncludeModsField);

        VBox dialogRoot = new VBox(18,
                ui.createSurface(Messages.get("settings.advanced"), Messages.get("settings.global.scope")),
                ui.createSurface(Messages.get("label.gameDir"), Messages.get("settings.global.rootHint"), dirBox),
                ui.createSurface(Messages.get("settings.global.resolution"), Messages.get("settings.global.resolutionHint"), resolutionBox),
                ui.createSurface(Messages.get("settings.global.server"), Messages.get("settings.global.serverHint"), serverField),
                ui.createSurface(Messages.get("settings.global.processors"), Messages.get("settings.global.processorHint"), processorField),
                ui.createSurface(Messages.get("settings.global.behavior"), null, behaviorBox),
                ui.createSurface(Messages.get("settings.global.backupTitle"),
                        Messages.get("settings.global.backupHint"),
                        backupBehaviorBox)
        );
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(24));

        Label status = new Label();
        status.setId("settings-global-status");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);

        Button saveBtn = new Button(Messages.get("settings.save"));
        saveBtn.setId("settings-global-save");
        saveBtn.getStyleClass().addAll("app-button", "primary-button");
        saveBtn.setOnAction(e -> {
            String configuredGameDir = dirField.getText().trim();
            if (configuredGameDir.isBlank()) {
                configuredGameDir = ECLConfig.getGameDir().getAbsolutePath();
            }

            int configuredWidth;
            int configuredHeight;
            int configuredProcessors;
            int configuredBackupKeepCount;
            try {
                configuredWidth = ui.parseRangedInt(widthField.getText(), Messages.get("settings.global.width"), 320, 16_384);
                configuredHeight = ui.parseRangedInt(heightField.getText(), Messages.get("settings.global.height"), 240, 16_384);
                configuredProcessors = processorField.getText().isBlank() ? 0
                        : ui.parseRangedInt(processorField.getText(), Messages.get("settings.global.processors"), 1,
                                Math.max(1, Runtime.getRuntime().availableProcessors()));
                configuredBackupKeepCount = ui.parseRangedInt(
                        backupKeepCountField.getText(), Messages.get("settings.global.keep"), 1, 100);
            } catch (IllegalArgumentException valueError) {
                status.setText(valueError.getMessage());
                return;
            }

            File requestedRoot = new File(configuredGameDir).getAbsoluteFile();
            File configuredRoot = requestedRoot.toPath().normalize().equals(
                    ECLConfig.getLegacyGameDir().toPath().toAbsolutePath().normalize())
                    ? ECLConfig.getGameDir() : requestedRoot;
            if (!configuredRoot.isDirectory() && !configuredRoot.mkdirs() && !configuredRoot.isDirectory()) {
                status.setText(Messages.get("settings.global.directoryInvalid"));
                return;
            }
            boolean saved = ui.pageFactory.saveFormSettings(() -> {
                ui.settingsManager.set(ECLConfig.KEY_GAME_DIR, configuredRoot.getAbsolutePath());
                ui.settingsManager.set(ECLConfig.KEY_GAME_WIDTH, configuredWidth);
                ui.settingsManager.set(ECLConfig.KEY_GAME_HEIGHT, configuredHeight);
                ui.settingsManager.set(ECLConfig.KEY_GAME_FULLSCREEN, fullscreenField.isSelected());
                ui.settingsManager.set(ECLConfig.KEY_QUICK_SERVER, serverField.getText().trim());
                ui.settingsManager.set(ECLConfig.KEY_PROCESSOR_COUNT, configuredProcessors);
                ui.settingsManager.set(ECLConfig.KEY_CLOSE_AFTER_LAUNCH, closeAfterLaunchField.isSelected());
                ui.settingsManager.set(ECLConfig.KEY_BACKUP_ON_LAUNCH, backupOnLaunchField.isSelected());
                ui.settingsManager.set(ECLConfig.KEY_BACKUP_KEEP_COUNT, configuredBackupKeepCount);
                ui.settingsManager.set(ECLConfig.KEY_BACKUP_INCLUDE_MODS, backupIncludeModsField.isSelected());
            }, ECLConfig.KEY_GAME_DIR, ECLConfig.KEY_GAME_WIDTH, ECLConfig.KEY_GAME_HEIGHT,
                    ECLConfig.KEY_GAME_FULLSCREEN, ECLConfig.KEY_QUICK_SERVER, ECLConfig.KEY_PROCESSOR_COUNT,
                    ECLConfig.KEY_CLOSE_AFTER_LAUNCH, ECLConfig.KEY_BACKUP_ON_LAUNCH,
                    ECLConfig.KEY_BACKUP_KEEP_COUNT, ECLConfig.KEY_BACKUP_INCLUDE_MODS);
            if (!saved) {
                status.setText(Messages.get("status.settingsSaveFailed.detail"));
                return;
            }
            ui.gameDir = configuredRoot;
            ui.gameWidth = configuredWidth;
            ui.gameHeight = configuredHeight;
            ui.gameFullscreen = fullscreenField.isSelected();
            ui.quickServer = serverField.getText().trim();
            ui.processorCount = configuredProcessors;
            ui.closeAfterLaunch = closeAfterLaunchField.isSelected();
            ui.backupOnLaunch = backupOnLaunchField.isSelected();
            ui.backupKeepCount = configuredBackupKeepCount;
            ui.backupIncludeMods = backupIncludeModsField.isSelected();
            ui.updateRuntimeSummary();
            ui.setStatus(Messages.get("status.settingsSaved"), Messages.get("settings.global.scope"));
            ui.renderActiveView();
            dialog.close();
        });

        finishDialog(dialog, dialogRoot, status, saveBtn, focusReturnTarget);
    }

    private void finishDialog(Stage dialog, VBox dialogRoot, Label status, Button saveBtn,
                              javafx.scene.Node focusReturnTarget) {
        Button cancelBtn = new Button(Messages.get("button.cancel"));
        cancelBtn.getStyleClass().addAll("app-button", "ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(12, saveBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        dialogRoot.getChildren().addAll(status, buttonBar);

        LauncherWindowChrome chrome = new LauncherWindowChrome(dialog);
        Label title = new Label(Messages.get("settings.advanced"));
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleBar = new HBox(12, title, spacer, chrome.createControls());
        titleBar.getStyleClass().addAll("window-title-bar", "settings-dialog-title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        chrome.installDragBehavior(titleBar);
        BorderPane frame = new BorderPane(ui.createWheelScrollPane(dialogRoot));
        frame.setTop(titleBar);
        Scene scene = new Scene(frame, 760, 650);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) dialog.close();
        });
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        ui.applyThemeToScene(scene);
        dialog.setOnHidden(event -> javafx.application.Platform.runLater(() -> {
            javafx.scene.Node target = focusReturnTarget != null && focusReturnTarget.getScene() != null
                    ? focusReturnTarget : ui.primaryStage.getScene().lookup("#settings-global-open");
            if (target != null) target.requestFocus();
        }));
        dialog.show();
    }

}
