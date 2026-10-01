package com.ecl.ui;

import com.ecl.game.DefaultGameRepository;
import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Edits the viewed instance's run directory independently of launch defaults. */
final class InstanceRunDirectoryPane extends VBox {
    private final LauncherUI ui;
    private final Runnable onSaved;
    private final ComboBox<String> mode = new ComboBox<>();
    private final TextField directory = new TextField();
    private final Label status = new Label();
    private final Button save;
    private String instanceId;

    InstanceRunDirectoryPane(LauncherUI ui, Runnable onSaved) {
        this.ui = ui;
        this.onSaved = onSaved;
        setSpacing(10);
        mode.setId("instance-directory-mode");
        mode.getItems().setAll(Messages.get("instances.directory.inherit"),
                Messages.get("instances.directory.isolated"), Messages.get("instances.directory.custom"));
        ui.applyFieldStyle(mode);
        directory.setId("instance-directory-path");
        directory.setPromptText(Messages.get("instances.directory.prompt"));
        ui.applyFieldStyle(directory);
        mode.setOnAction(event -> directory.setDisable(mode.getSelectionModel().getSelectedIndex() != 2));
        Button browse = ui.createActionButton(Messages.get("settings.browse"), "secondary-button", this::browse);
        browse.disableProperty().bind(directory.disabledProperty());
        HBox directoryRow = new HBox(10, directory, browse);
        HBox.setHgrow(directory, Priority.ALWAYS);
        save = ui.createActionButton(Messages.get("instances.directory.save"), "primary-button", this::save);
        save.setId("instance-directory-save");
        Button reload = ui.createActionButton(Messages.get("instances.config.reset"), "ghost-button",
                () -> load(instanceId));
        HBox actions = new HBox(10, save, reload);
        actions.setAlignment(Pos.CENTER_LEFT);
        status.setId("instance-directory-status");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        getChildren().addAll(mode, directoryRow, status, actions);
    }

    void load(String profileId) {
        instanceId = profileId;
        save.setDisable(true);
        try {
            InstanceGameSettings settings = new InstanceGameSettingsStore().load(ui.gameRepository().instanceRoot(instanceId));
            mode.getSelectionModel().select(!settings.overridesRunningDirectory() ? 0 : settings.hasCustomDirectory() ? 2 : 1);
            directory.setText(settings.hasCustomDirectory() ? settings.runningDirectory() : "");
            directory.setDisable(mode.getSelectionModel().getSelectedIndex() != 2);
            boolean modpack = ui.gameRepository().resolve(instanceId).isModpack();
            mode.setDisable(modpack);
            directory.setDisable(modpack || directory.isDisabled());
            boolean running = ui.isVersionRunning(instanceId);
            save.setDisable(modpack || running);
            status.setText(Messages.get(modpack ? "instances.directory.modpack"
                    : running ? "instances.directory.running" : "instances.directory.scope"));
        } catch (IOException error) {
            status.setText(Messages.format("instances.config.loadFailed", ui.cleanMessage(error)));
        }
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.get("instances.directory.prompt"));
        File initial = ui.prepareChooserDir(directory.getText());
        if (initial != null) chooser.setInitialDirectory(initial);
        File selected = chooser.showDialog(ui.primaryStage);
        if (selected != null) directory.setText(selected.getAbsolutePath());
    }

    private void save() {
        if (instanceId == null || ui.isVersionRunning(instanceId)) {
            status.setText(Messages.get("instances.directory.running"));
            return;
        }
        try {
            DefaultGameRepository repository = ui.gameRepository();
            if (repository.resolve(instanceId).isModpack()) {
                status.setText(Messages.get("instances.directory.modpack"));
                return;
            }
            switch (mode.getSelectionModel().getSelectedIndex()) {
                case 0 -> repository.inheritRunDirectoryPolicy(instanceId);
                case 1 -> repository.setIsolated(instanceId);
                case 2 -> {
                    String value = directory.getText().trim();
                    if (value.isBlank() || !Path.of(value).isAbsolute()) {
                        status.setText(Messages.get("instances.directory.required"));
                        return;
                    }
                    repository.setCustomRunDirectory(instanceId, Path.of(value));
                }
                default -> { return; }
            }
            ui.updateRuntimeSummary();
            onSaved.run();
            status.setText(Messages.get("instances.config.saved"));
        } catch (IOException | IllegalArgumentException error) {
            status.setText(Messages.format("instances.config.saveFailed", ui.cleanMessage(error)));
        }
    }
}
