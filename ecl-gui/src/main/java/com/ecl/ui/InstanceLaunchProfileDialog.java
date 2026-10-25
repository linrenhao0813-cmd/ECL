package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.game.InstanceLaunchProfileStore;
import com.ecl.launch.GameProcessMarker;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.JvmArgumentPolicy;
import com.ecl.util.Messages;
import com.ecl.util.TextUtil;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Edits the launch profile of the instance currently shown in the details pane. */
final class InstanceLaunchProfileDialog {
    private final LauncherUI ui;
    private final String profileId;
    private final Button focusOwner;
    private final Runnable onSaved;
    private final Path instanceRoot;
    private final InstanceLaunchProfileStore profiles;
    private final Dialog<Void> dialog = new Dialog<>();
    private final RadioButton javaAuto = new RadioButton(Messages.get("instances.java.auto"));
    private final RadioButton javaCustom = new RadioButton(Messages.get("instances.java.custom"));
    private final TextField javaPath = new TextField();
    private final Button browseJava = new Button(Messages.get("settings.browse"));
    private final RadioButton memoryAuto = new RadioButton(Messages.get("instances.memory.auto"));
    private final RadioButton memoryCustom = new RadioButton(Messages.get("instances.memory.custom"));
    private final TextField memory = new TextField();
    private final TextField jvmArguments = new TextField();
    private final Label effective = new Label();
    private final Label status = new Label();
    private List<Object> savedValues;
    private boolean skipClosePrompt;
    private boolean closed;
    private boolean saving;
    private boolean corruptProfileConfirmed;

    private InstanceLaunchProfileDialog(LauncherUI ui, String profileId, Button focusOwner,
                                        Runnable onSaved) {
        this.ui = ui;
        this.profileId = profileId;
        this.focusOwner = focusOwner;
        this.onSaved = onSaved;
        this.instanceRoot = ui.resolveVersionInstanceRoot(profileId).toPath();
        this.profiles = ui.controller.instanceLaunchProfiles();
    }

    static void show(LauncherUI ui, String profileId, Button focusOwner, Runnable onSaved) {
        new InstanceLaunchProfileDialog(ui, profileId, focusOwner, onSaved).show();
    }

    private void show() {
        InstanceLaunchProfile defaults;
        try {
            defaults = currentDefaults();
        } catch (IllegalArgumentException invalidDefaults) {
            showError(Messages.format("instances.launch.saveFailed", invalidDefaults.getMessage()));
            return;
        }
        configure(defaults, true);
        dialog.initOwner(ui.primaryStage);
        dialog.setTitle(Messages.get("instances.launch.title"));
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(buildContent());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        javafx.scene.Node defaultCancel = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        defaultCancel.setVisible(false);
        defaultCancel.setManaged(false);
        dialog.setOnCloseRequest(event -> {
            if (saving) return;
            if (skipClosePrompt || !hasUnsavedChanges()) return;
            event.consume();
            handleWindowClose();
        });
        dialog.setOnHidden(event -> {
            closed = true;
            Platform.runLater(focusOwner::requestFocus);
        });
        dialog.setOnShown(shown -> dialog.getDialogPane().getScene().addEventFilter(
                javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        if (saving || !hasUnsavedChanges()) cancel();
                        else handleWindowClose();
                        event.consume();
                    }
                }));
        dialog.getDialogPane().setDisable(true);
        Thread.ofVirtual().name("ecl-launch-profile-read").start(() -> {
            try {
                InstanceLaunchProfile loaded = Files.isRegularFile(profiles.profileFile(instanceRoot))
                        ? profiles.load(instanceRoot) : defaults;
                Platform.runLater(() -> {
                    if (closed) return;
                    configure(loaded, true);
                    dialog.getDialogPane().setDisable(false);
                });
            } catch (IOException damaged) {
                Platform.runLater(() -> {
                    if (closed) return;
                    if (!confirmRebuild(damaged)) {
                        cancel();
                        return;
                    }
                    corruptProfileConfirmed = true;
                    dialog.getDialogPane().setDisable(false);
                });
            }
        });
        dialog.showAndWait();
    }

    private VBox buildContent() {
        ToggleGroup javaModes = new ToggleGroup();
        javaAuto.setToggleGroup(javaModes);
        javaCustom.setToggleGroup(javaModes);
        ToggleGroup memoryModes = new ToggleGroup();
        memoryAuto.setToggleGroup(memoryModes);
        memoryCustom.setToggleGroup(memoryModes);
        ui.applyFieldStyle(javaPath);
        ui.applyFieldStyle(memory);
        ui.applyFieldStyle(jvmArguments);
        javaPath.setId("instance-launch-java-path");
        memory.setId("instance-launch-memory");
        jvmArguments.setId("instance-launch-jvm-arguments");
        javaPath.setAccessibleText(Messages.get("instances.config.java"));
        memory.setAccessibleText(Messages.get("instances.launch.memoryLabel"));
        jvmArguments.setAccessibleText(Messages.get("instances.config.jvm"));
        browseJava.setId("instance-launch-java-browse");
        browseJava.getStyleClass().addAll("app-button", "secondary-button");
        browseJava.setOnAction(event -> chooseJava());
        memory.setPromptText(Messages.format("home.memoryAutoHint", ECLConfig.calculateAutoMemoryMb()));
        jvmArguments.setPromptText(Messages.get("instances.jvm.prompt"));
        effective.setId("instance-launch-effective");
        effective.setWrapText(true);
        status.setId("instance-launch-status");
        status.setWrapText(true);

        javaAuto.setOnAction(event -> updateFormState());
        javaCustom.setOnAction(event -> updateFormState());
        memoryAuto.setOnAction(event -> updateFormState());
        memoryCustom.setOnAction(event -> updateFormState());
        javaPath.textProperty().addListener((observable, oldValue, newValue) -> updateEffective());
        memory.textProperty().addListener((observable, oldValue, newValue) -> updateEffective());
        jvmArguments.textProperty().addListener((observable, oldValue, newValue) -> updateEffective());

        Button save = actionButton("settings.save", "primary-button", this::save);
        save.setId("instance-launch-save");
        Button cancel = actionButton("settings.cancel", "ghost-button", this::cancel);
        cancel.setId("instance-launch-cancel");
        Button reset = actionButton("instances.launch.resetDefaults", "ghost-button", this::resetToDefaults);
        reset.setId("instance-launch-reset-defaults");
        HBox actions = new HBox(8, save, cancel, reset);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox form = new VBox(10,
                new Label(Messages.format("instances.launch.instance", ui.versionManager
                        .getVersionDisplayName(profileId), profileId)),
                ui.createControlRow(Messages.get("instances.config.java"),
                        new VBox(8, new HBox(12, javaAuto, javaCustom), new HBox(8, javaPath, browseJava))),
                ui.createControlRow(Messages.get("instances.launch.memoryLabel"),
                        new VBox(8, new HBox(12, memoryAuto, memoryCustom), memory)),
                ui.createControlRow(Messages.get("instances.config.jvm"), jvmArguments),
                ui.createControlRow(Messages.get("instances.launch.effective"), effective),
                status,
                actions);
        form.setPrefWidth(660);
        form.setId("instance-launch-profile-form");
        return form;
    }

    private void configure(InstanceLaunchProfile profile, boolean updateBaseline) {
        javaAuto.setSelected(profile.javaMode() == InstanceLaunchProfile.JavaMode.AUTO);
        javaCustom.setSelected(profile.javaMode() == InstanceLaunchProfile.JavaMode.CUSTOM);
        javaPath.setText(profile.javaPath());
        memoryAuto.setSelected(profile.memoryMode() == InstanceLaunchProfile.MemoryMode.AUTO);
        memoryCustom.setSelected(profile.memoryMode() == InstanceLaunchProfile.MemoryMode.CUSTOM);
        memory.setText(profile.memoryMode() == InstanceLaunchProfile.MemoryMode.AUTO
                ? "" : Integer.toString(profile.maxMemoryMb()));
        jvmArguments.setText(TextUtil.formatCommandLine(profile.customJvmArguments()));
        if (updateBaseline) savedValues = draftValues();
        updateFormState();
    }

    private void updateFormState() {
        javaPath.setDisable(!javaCustom.isSelected());
        browseJava.setDisable(!javaCustom.isSelected());
        memory.setDisable(!memoryCustom.isSelected());
        updateEffective();
    }

    private void updateEffective() {
        String java = javaAuto.isSelected()
                ? Messages.get("instances.launch.effective.javaAuto")
                : javaPath.getText();
        String memoryValue = memoryAuto.isSelected()
                ? Messages.format("instances.launch.effective.memoryAuto", ECLConfig.calculateAutoMemoryMb())
                : Messages.format("instances.launch.effective.memoryCustom", memory.getText());
        String arguments;
        try {
            List<String> parsed = TextUtil.parseCommandLine(jvmArguments.getText());
            JvmArgumentPolicy.requireSafe(parsed);
            arguments = parsed.isEmpty()
                    ? Messages.get("instances.launch.none") : String.join("  ·  ", parsed);
        } catch (IllegalArgumentException error) {
            arguments = Messages.format("instances.launch.effective.invalid", error.getMessage());
        }
        effective.setText(Messages.format("instances.launch.effective.summary", java,
                memoryValue, arguments));
    }

    private void chooseJava() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("instances.java.prompt"));
        File initial = ui.prepareChooserDir(javaPath.getText());
        if (initial != null) chooser.setInitialDirectory(initial);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "*.exe", "java"));
        File selected = chooser.showOpenDialog(dialog.getOwner());
        if (selected != null) javaPath.setText(selected.getAbsolutePath());
    }

    private void resetToDefaults() {
        try {
            configure(currentDefaults(), false);
            status.setText(Messages.get("instances.launch.defaultsLoaded"));
        } catch (IllegalArgumentException invalidDefaults) {
            status.setText(Messages.format("instances.launch.saveFailed", invalidDefaults.getMessage()));
        }
    }

    private InstanceLaunchProfile currentDefaults() {
        String configuredJava = ui.javaPath == null ? "" : ui.javaPath.trim();
        int configuredMemory = ui.maxMemoryMb;
        if (configuredMemory != ECLConfig.AUTO_MEMORY_MB
                && (configuredMemory < ECLConfig.MIN_GAME_MEMORY_MB
                || configuredMemory > ECLConfig.MAX_GAME_MEMORY_MB)) {
            configuredMemory = ECLConfig.AUTO_MEMORY_MB;
        }
        List<String> arguments = TextUtil.parseCommandLine(ui.extraJvmArgs == null ? "" : ui.extraJvmArgs);
        JvmArgumentPolicy.requireSafe(arguments);
        return new InstanceLaunchProfile(InstanceLaunchProfile.CURRENT_SCHEMA_VERSION,
                configuredJava.isBlank() ? InstanceLaunchProfile.JavaMode.AUTO
                        : InstanceLaunchProfile.JavaMode.CUSTOM,
                configuredJava,
                configuredMemory == ECLConfig.AUTO_MEMORY_MB ? InstanceLaunchProfile.MemoryMode.AUTO
                        : InstanceLaunchProfile.MemoryMode.CUSTOM,
                configuredMemory,
                arguments);
    }

    private void save() {
        InstanceLaunchProfile profile;
        try {
            profile = readForm();
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
            return;
        }
        if (saving) return;
        saving = true;
        dialog.getDialogPane().setDisable(true);
        Thread.ofVirtual().name("ecl-launch-profile-save").start(() -> {
            try {
                if (profile.javaMode() == InstanceLaunchProfile.JavaMode.CUSTOM
                        && !JavaRuntimeUtil.isUsableJavaPath(profile.javaPath())) {
                    throw new IOException(Messages.get("instances.config.javaInvalid"));
                }
                VersionProfileModInstanceContext instance = VersionProfileModInstanceContext.load(profileId,
                        ECLConfig.getVersionsDir().toPath(), ui.getConfiguredGameRootDir().toPath(),
                        ui.gameRepository().runDirectory(profileId));
                if (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)
                        || ui.isVersionRunning(profileId) || GameProcessMarker.isRunning(instance.gameDirectory())) {
                    throw new IOException(Messages.get("instances.launch.busy"));
                }
                AutoCloseable operation = ui.controller.instanceOperations().tryAcquire(instance.instanceId());
                if (operation == null) throw new IOException(Messages.get("instances.launch.busy"));
                try (operation; InstanceOperationLease gameLock = InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
                    if (gameLock == null || ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)
                            || ui.isVersionRunning(profileId) || GameProcessMarker.isRunning(instance.gameDirectory())) {
                        throw new IOException(Messages.get("instances.launch.busy"));
                    }
                    profiles.save(instanceRoot, profile);
                }
                ui.controller.invalidateLaunchVersion(profileId);
                Platform.runLater(() -> {
                    if (closed) return;
                    saving = false;
                    corruptProfileConfirmed = false;
                    ui.updateRuntimeSummary();
                    onSaved.run();
                    ui.setStatus(Messages.get("instances.config.saved"), profileId);
                    skipClosePrompt = true;
                    dialog.close();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closed) return;
                    saving = false;
                    dialog.getDialogPane().setDisable(false);
                    status.setText(Messages.format("instances.launch.saveFailed", ui.cleanMessage(error)));
                });
            }
        });
    }

    private InstanceLaunchProfile readForm() {
        String selectedJava = javaCustom.isSelected() ? javaPath.getText().trim() : "";
        int selectedMemory = ECLConfig.AUTO_MEMORY_MB;
        if (memoryCustom.isSelected()) {
            try {
                selectedMemory = Integer.parseInt(memory.getText().trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(Messages.get("instances.launch.memoryInvalid"), invalid);
            }
            if (selectedMemory < ECLConfig.MIN_GAME_MEMORY_MB
                    || selectedMemory > ECLConfig.MAX_GAME_MEMORY_MB) {
                throw new IllegalArgumentException(Messages.format("instances.launch.memoryRange",
                        ECLConfig.MIN_GAME_MEMORY_MB, ECLConfig.MAX_GAME_MEMORY_MB));
            }
        }
        List<String> arguments;
        try {
            arguments = TextUtil.parseCommandLine(jvmArguments.getText());
            JvmArgumentPolicy.requireSafe(arguments);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(Messages.format("instances.config.jvmInvalid",
                    invalid.getMessage()), invalid);
        }
        return new InstanceLaunchProfile(InstanceLaunchProfile.CURRENT_SCHEMA_VERSION,
                javaCustom.isSelected() ? InstanceLaunchProfile.JavaMode.CUSTOM
                        : InstanceLaunchProfile.JavaMode.AUTO,
                selectedJava,
                memoryCustom.isSelected() ? InstanceLaunchProfile.MemoryMode.CUSTOM
                        : InstanceLaunchProfile.MemoryMode.AUTO,
                selectedMemory,
                arguments);
    }

    private boolean hasUnsavedChanges() {
        return corruptProfileConfirmed || !savedValues.equals(draftValues());
    }

    private List<Object> draftValues() {
        return List.of(javaCustom.isSelected(), javaPath.getText(), memoryCustom.isSelected(),
                memory.getText(), jvmArguments.getText());
    }

    private void handleWindowClose() {
        ButtonType save = new ButtonType(Messages.get("settings.save"));
        ButtonType discard = new ButtonType(Messages.get("settings.discard"));
        ButtonType continueEditing = new ButtonType(Messages.get("instances.launch.continueEditing"));
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.get("instances.launch.unsavedBody"), save, discard, continueEditing);
        alert.initOwner(dialog.getOwner());
        alert.setTitle(Messages.get("instances.launch.unsavedTitle"));
        alert.setHeaderText(Messages.get("instances.launch.unsavedHeader"));
        ButtonType choice = alert.showAndWait().orElse(continueEditing);
        if (choice == save) save();
        else if (choice == discard) {
            skipClosePrompt = true;
            dialog.close();
        }
    }

    private void cancel() {
        skipClosePrompt = true;
        dialog.close();
    }

    private boolean confirmRebuild(IOException damaged) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.format("instances.launch.corruptBody", ui.cleanMessage(damaged)),
                new ButtonType(Messages.get("instances.launch.rebuild")), ButtonType.CANCEL);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("instances.launch.corruptTitle"));
        alert.setHeaderText(Messages.get("instances.launch.corruptHeader"));
        return alert.showAndWait().filter(choice -> choice != ButtonType.CANCEL).isPresent();
    }

    private Button actionButton(String textKey, String style, Runnable action) {
        Button button = new Button(Messages.get(textKey));
        button.getStyleClass().addAll("app-button", style);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("instances.launch.title"));
        alert.showAndWait();
    }
}
