package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shared global settings form for the settings page and the legacy dialog. */
final class GlobalGameSettingsPane extends VBox {
    private final Label status;
    private final Button saveBtn;
    private final Supplier<List<Object>> values;
    private List<Object> savedValues;
    private final BooleanSupplier saveAction;
    private Runnable onSaved = () -> { };
    private Runnable onDiscard = () -> { };

    GlobalGameSettingsPane(LauncherUI ui, boolean inline) {
        setSpacing(14);
        setId("settings-global-form");
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
            File selected = chooser.showDialog(getScene() == null ? ui.primaryStage : getScene().getWindow());
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

        VBox launchOptions = new VBox(12,
                ui.createControlRow(Messages.get("settings.global.resolution"), resolutionBox),
                fullscreenField,
                ui.createControlRow(Messages.get("settings.global.server"), serverField),
                ui.createControlRow(Messages.get("settings.global.processors"), processorField),
                closeAfterLaunchField);
        getChildren().add(ui.createSurface(Messages.get("label.gameDir"),
                Messages.get("settings.global.rootHint"), dirBox));
        if (inline) {
            getChildren().addAll(group(Messages.get("settings.global.behavior"), launchOptions, "settings-launch-group"),
                    group(Messages.get("settings.global.backupTitle"), backupBehaviorBox, "settings-backup-group"));
        } else {
            getChildren().addAll(ui.createSurface(Messages.get("settings.global.behavior"), null, launchOptions),
                    ui.createSurface(Messages.get("settings.global.backupTitle"),
                            Messages.get("settings.global.backupHint"), backupBehaviorBox));
        }
        status = new Label();
        status.setId("settings-global-status");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());

        saveBtn = new Button(Messages.get("settings.save"));
        saveBtn.setId("settings-global-save");
        saveBtn.getStyleClass().addAll("app-button", "primary-button");
        values = () -> java.util.List.of(dirField.getText(), widthField.getText(), heightField.getText(),
                fullscreenField.isSelected(), serverField.getText(), processorField.getText(),
                closeAfterLaunchField.isSelected(), backupOnLaunchField.isSelected(),
                backupKeepCountField.getText(), backupIncludeModsField.isSelected());
        savedValues = values.get();
        saveAction = () -> {
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
                return false;
            }

            File requestedRoot = new File(configuredGameDir).getAbsoluteFile();
            File configuredRoot = requestedRoot.toPath().normalize().equals(
                    ECLConfig.getLegacyGameDir().toPath().toAbsolutePath().normalize())
                    ? ECLConfig.getGameDir() : requestedRoot;
            if (!configuredRoot.isDirectory() && !configuredRoot.mkdirs() && !configuredRoot.isDirectory()) {
                status.setText(Messages.get("settings.global.directoryInvalid"));
                return false;
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
                return false;
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
            dirField.setText(configuredRoot.getAbsolutePath());
            savedValues = values.get();
            onSaved.run();
            return true;
        };
        saveBtn.setOnAction(event -> save());
        if (inline) {
            Button discard = ui.createActionButton(Messages.get("settings.discard"), "ghost-button", () -> onDiscard.run());
            discard.setId("settings-global-discard");
            getChildren().addAll(status, new HBox(10, saveBtn, discard));
        }

    }

    boolean hasUnsavedChanges() {
        return !savedValues.equals(values.get());
    }

    boolean save() {
        return saveAction.getAsBoolean();
    }

    void setOnSaved(Runnable action) {
        onSaved = action;
    }

    void setOnDiscard(Runnable action) {
        onDiscard = action;
    }

    Label statusLabel() {
        return status;
    }

    Button saveButton() {
        return saveBtn;
    }

    private static TitledPane group(String title, VBox content, String id) {
        TitledPane group = new TitledPane(title, content);
        group.setId(id);
        group.getStyleClass().add("settings-group");
        group.setAnimated(false);
        group.setExpanded(false);
        return group;
    }
}
