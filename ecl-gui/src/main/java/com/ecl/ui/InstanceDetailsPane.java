package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.launcher.VersionManager;
import com.ecl.pack.PackFormat;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.JvmArgumentPolicy;
import com.ecl.util.Messages;
import com.ecl.util.TextUtil;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Right-hand workspace of the instance manager: overview, per-instance launch configuration and
 * maintenance actions for one instance. Editing an instance never changes the launch target on its
 * own — actions that operate on the launch target stay disabled until the user retargets explicitly.
 */
final class InstanceDetailsPane extends VBox {
    private final LauncherUI ui;
    private final Runnable onDisplayChanged;
    private String instanceId;
    private boolean loadingConfig;
    private final ChangeListener<String> launchTargetListener =
            (observable, previous, current) -> refreshTargetState();

    private final TextField displayNameField = new TextField();
    private final CheckBox favoriteBox = new CheckBox(Messages.get("instances.display.favorite"));
    private final ImageView coverPreview = new ImageView();
    private final Label displayStatus = new Label();
    private String coverPath = "";
    private final Button chooseCoverButton;
    private final Button clearCoverButton;
    private final Button saveDisplayButton;

    private final Label title = new Label();
    private final Label meta = new Label();
    private final Label targetBadge = new Label();
    private final Button targetButton;
    private final Button openFolderButton;
    private final Label emptyHint = new Label(Messages.get("instances.detail.empty"));

    private final VBox overviewRows = new VBox(8);
    private final Label runtimeStatus = new Label();

    private final ToggleGroup javaMode = new ToggleGroup();
    private final RadioButton javaAuto;
    private final RadioButton javaCustom;
    private final TextField javaPathField = new TextField();
    private final ToggleGroup memoryMode = new ToggleGroup();
    private final RadioButton memoryAuto;
    private final RadioButton memoryCustom;
    private final TextField memoryField = new TextField();
    private final TextField jvmField = new TextField();
    private final Label configStatus = new Label();
    private final Button saveConfigButton;
    private final Button reloadConfigButton;

    private final VBox maintenanceActions = new VBox(10);
    private final Label maintenanceHint = new Label();

    private final TabPane tabs = new TabPane();
    private final VBox body = new VBox(14);

    InstanceDetailsPane(LauncherUI ui, Runnable onDisplayChanged) {
        this.ui = ui;
        this.onDisplayChanged = onDisplayChanged;
        getStyleClass().add("instance-details");
        setSpacing(14);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        title.getStyleClass().add("section-title");
        meta.getStyleClass().add("section-subtitle");
        meta.setWrapText(true);
        targetBadge.getStyleClass().add("instance-target-badge");
        targetButton = ui.createActionButton(Messages.get("instances.setTarget"),
                "primary-button", this::setAsLaunchTarget);
        targetButton.setId("instance-set-target");
        openFolderButton = ui.createActionButton(Messages.get("local.instances.openFolder"),
                "ghost-button", this::openInstanceFolder);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(10, new VBox(3, title, meta), headerSpacer,
                targetBadge, openFolderButton, targetButton);
        header.setAlignment(Pos.CENTER_LEFT);

        javaAuto = new RadioButton(Messages.get("instances.java.auto"));
        javaCustom = new RadioButton(Messages.get("instances.java.custom"));
        javaAuto.setToggleGroup(javaMode);
        javaCustom.setToggleGroup(javaMode);
        javaCustom.setId("instance-java-custom");
        javaPathField.setId("instance-java-path");
        javaPathField.setPromptText(Messages.get("instances.java.prompt"));
        ui.applyFieldStyle(javaPathField);
        Button detectJava = ui.createActionButton(Messages.get("settings.detect"),
                "secondary-button", () -> javaPathField.setText(JavaRuntimeUtil.detectSystemJavaExecutable()));
        Button browseJava = ui.createActionButton(Messages.get("settings.browse"),
                "secondary-button", this::chooseJavaExecutable);
        HBox javaRow = new HBox(10, javaPathField, detectJava, browseJava);
        HBox.setHgrow(javaPathField, Priority.ALWAYS);
        javaMode.selectedToggleProperty().addListener((observable, previous, selected) -> updateJavaRowState());

        memoryAuto = new RadioButton(Messages.get("instances.memory.auto"));
        memoryCustom = new RadioButton(Messages.get("instances.memory.custom"));
        memoryAuto.setToggleGroup(memoryMode);
        memoryCustom.setToggleGroup(memoryMode);
        memoryAuto.setId("instance-memory-auto");
        memoryCustom.setId("instance-memory-custom");
        memoryField.setId("instance-memory-value");
        memoryMode.selectedToggleProperty().addListener(
                (observable, previous, selected) -> updateMemoryRowState());
        memoryField.setPromptText(Messages.get("instances.memory.prompt"));
        memoryField.setMaxWidth(180);
        ui.applyFieldStyle(memoryField);
        HBox memoryRow = new HBox(10, memoryAuto, memoryCustom, memoryField);
        memoryRow.setAlignment(Pos.CENTER_LEFT);

        jvmField.setPromptText(Messages.get("instances.jvm.prompt"));
        jvmField.setId("instance-jvm-arguments");
        ui.applyFieldStyle(jvmField);

        configStatus.getStyleClass().add("status-detail");
        configStatus.setWrapText(true);
        saveConfigButton = ui.createActionButton(Messages.get("instances.config.save"),
                "primary-button", this::saveLaunchConfig);
        saveConfigButton.setId("instance-config-save");
        reloadConfigButton = ui.createActionButton(Messages.get("instances.config.reset"),
                "ghost-button", this::loadLaunchConfig);
        HBox configActions = new HBox(10, saveConfigButton, reloadConfigButton);
        configActions.setAlignment(Pos.CENTER_LEFT);

        VBox configTab = new VBox(14,
                ui.createSurface(Messages.get("instances.config.java"), null,
                        javaAuto, javaCustom, javaRow),
                ui.createSurface(Messages.get("instances.config.memory"),
                        Messages.get("instances.config.scope"), memoryRow),
                ui.createSurface(Messages.get("instances.config.jvm"), null, jvmField),
                configStatus, configActions);
        configTab.getStyleClass().add("instance-config-tab");

        maintenanceHint.getStyleClass().add("status-detail");
        maintenanceHint.setWrapText(true);
        maintenanceActions.getChildren().addAll(maintenanceHint);
        VBox maintenanceTab = new VBox(14,
                ui.createSurface(Messages.get("instances.maintenance.title"),
                        Messages.get("instances.maintenance.subtitle"), maintenanceActions));
        maintenanceTab.getStyleClass().add("instance-maintenance-tab");

        ui.applyFieldStyle(displayNameField);
        coverPreview.setFitWidth(72);
        coverPreview.setFitHeight(72);
        coverPreview.setPreserveRatio(true);
        coverPreview.getStyleClass().add("instance-cover-preview");
        chooseCoverButton = ui.createActionButton(Messages.get("instances.display.chooseCover"),
                "secondary-button", this::chooseCover);
        clearCoverButton = ui.createActionButton(Messages.get("instances.display.clearCover"),
                "ghost-button", this::clearCover);
        saveDisplayButton = ui.createActionButton(Messages.get("instances.display.save"),
                "secondary-button", this::saveDisplay);
        saveDisplayButton.setId("instance-display-save");
        displayStatus.getStyleClass().add("status-detail");
        displayStatus.setWrapText(true);
        HBox coverRow = new HBox(12, coverPreview, new VBox(8, chooseCoverButton, clearCoverButton));
        coverRow.setAlignment(Pos.CENTER_LEFT);
        favoriteBox.getStyleClass().add("instance-favorite-box");
        VBox displayBox = new VBox(12,
                ui.createControlRow(Messages.get("instances.display.name"), displayNameField),
                favoriteBox,
                coverRow,
                displayStatus,
                saveDisplayButton);
        displayBox.getStyleClass().add("instance-display-box");
        VBox overviewTab = new VBox(14,
                ui.createSurface(Messages.get("instances.tab.overview"),
                        Messages.get("instances.detail.meta2"), overviewRows, runtimeStatus),
                ui.createSurface(Messages.get("instances.display.title"),
                        Messages.get("instances.display.subtitle"), displayBox));

        tabs.getTabs().addAll(
                new Tab(Messages.get("instances.tab.overview"), overviewTab),
                new Tab(Messages.get("instances.tab.config"), configTab),
                new Tab(Messages.get("instances.tab.maintenance"), maintenanceTab));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("mod-tabs");
        tabs.setId("instance-detail-tabs");

        runtimeStatus.getStyleClass().add("status-detail");
        emptyHint.getStyleClass().add("status-detail");
        emptyHint.setWrapText(true);

        body.getChildren().addAll(header, tabs);
        body.getStyleClass().add("instance-details-body");
        body.setMinWidth(0);
        body.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(body, emptyHint);
        sceneProperty().addListener((observable, previous, current) -> {
            if (previous != null) {
                ui.instanceSelection.launchTargetProperty().removeListener(launchTargetListener);
            }
            if (current != null) {
                ui.instanceSelection.launchTargetProperty().addListener(launchTargetListener);
                refreshTargetState();
            }
        });
        setInstance(null);
    }

    void setInstance(String profileId) {
        instanceId = profileId == null || profileId.isBlank() ? null : profileId;
        boolean present = instanceId != null;
        emptyHint.setVisible(!present);
        emptyHint.setManaged(!present);
        body.setVisible(present);
        body.setManaged(present);
        if (!present) {
            return;
        }
        refresh();
    }

    String instanceId() {
        return instanceId;
    }

    /** Reloads overview, launch config and maintenance actions for the current instance. */
    void refresh() {
        if (instanceId == null) {
            return;
        }
        VersionManager.LocalVersionProfile profile = localProfile(instanceId);
        String minecraftVersion = profile == null ? instanceId : profile.minecraftVersion();
        String loader = profile == null || profile.loader().isBlank()
                ? GuiMessages.get("forest.vanilla")
                : ui.loaderChoiceForProfile(instanceId).displayName;

        title.setText(ui.versionManager.getVersionDisplayName(instanceId));
        meta.setText(Messages.format("instances.detail.meta", minecraftVersion, loader));
        updateTargetBadge();
        updateOverviewRows(minecraftVersion, loader);
        loadDisplaySettings();
        loadLaunchConfig();
        rebuildMaintenance();
    }

    private void loadDisplaySettings() {
        com.ecl.game.InstanceDisplayMetadata display = ui.instanceDisplay.get(instanceId);
        displayNameField.setPromptText(ui.versionManager.getVersionDisplayName(instanceId));
        displayNameField.setText(display.displayName());
        favoriteBox.setSelected(display.favorite());
        coverPath = display.coverImage();
        displayStatus.setText("");
        updateCoverPreview();
    }

    private void updateCoverPreview() {
        javafx.scene.image.Image image = InstanceCoverImages.load(coverPath);
        coverPreview.setImage(image);
        LauncherUiFactory.setVisible(coverPreview, image != null);
        clearCoverButton.setDisable(coverPath == null || coverPath.isBlank());
    }

    private void chooseCover() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("instances.display.chooseCover"));
        File initial = ui.prepareChooserDir(coverPath);
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "PNG / JPEG", "*.png", "*.jpg", "*.jpeg"));
        File selected = chooser.showOpenDialog(ui.primaryStage);
        if (selected == null) {
            return;
        }
        coverPath = selected.getAbsolutePath();
        InstanceCoverImages.invalidate(coverPath);
        updateCoverPreview();
    }

    private void clearCover() {
        coverPath = "";
        updateCoverPreview();
    }

    private void saveDisplay() {
        if (instanceId == null) {
            return;
        }
        String name = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        try {
            ui.instanceDisplay.put(ui.instanceDisplay.get(instanceId)
                    .withDisplayName(name)
                    .withFavorite(favoriteBox.isSelected())
                    .withCoverImage(coverPath));
            onDisplayChanged.run();
            displayStatus.setText(Messages.get("instances.display.saved"));
        } catch (IllegalStateException error) {
            displayStatus.setText(error.getMessage());
        }
    }

    private void updateTargetBadge() {
        boolean isTarget = instanceId.equals(ui.getSelectedVersion());
        targetBadge.setText(Messages.get(isTarget
                ? "instances.target.current" : "instances.target.other"));
        targetBadge.getStyleClass().removeAll(
                "instance-target-badge-current", "instance-target-badge-other");
        targetBadge.getStyleClass().add(isTarget
                ? "instance-target-badge-current" : "instance-target-badge-other");
        targetButton.setDisable(isTarget);
    }

    /** Target changes refresh permissions without discarding unsaved launch or display edits. */
    private void refreshTargetState() {
        if (instanceId == null) {
            return;
        }
        updateTargetBadge();
        VersionManager.LocalVersionProfile profile = localProfile(instanceId);
        String loader = profile == null || profile.loader().isBlank()
                ? GuiMessages.get("forest.vanilla") : ui.loaderChoiceForProfile(instanceId).displayName;
        updateOverviewRows(profile == null ? instanceId : profile.minecraftVersion(), loader);
        rebuildMaintenance();
        onDisplayChanged.run();
    }

    private void setAsLaunchTarget() {
        ui.setLaunchTarget(instanceId);
        ui.updateRuntimeSummary();
        updateTargetBadge();
        rebuildMaintenance();
        ui.setStatus(Messages.get("instances.target.switched"),
                ui.versionManager.getVersionDisplayName(instanceId));
    }

    private void openInstanceFolder() {
        ui.openLocalFolder(ui.resolveVersionGameDir(instanceId),
                Messages.get("local.instances.folderTitle"));
    }

    private void updateOverviewRows(String minecraftVersion, String loader) {
        overviewRows.getChildren().setAll(
                ui.createInfoRow(Messages.get("label.gameVersion"),
                        ui.createStaticValueLabel(minecraftVersion)),
                ui.createInfoRow(Messages.get("local.instances.folderTitle"),
                        ui.createStaticValueLabel(ui.resolveVersionGameDir(instanceId).getAbsolutePath())),
                ui.createInfoRow(Messages.get("instances.detail.profileId"),
                        ui.createStaticValueLabel(instanceId)),
                ui.createInfoRow(Messages.get("instances.detail.loader"),
                        ui.createStaticValueLabel(loader)),
                ui.createInfoRow(Messages.get("instances.detail.target"),
                        ui.createStaticValueLabel(instanceId.equals(ui.getSelectedVersion())
                                ? Messages.get("instances.target.current")
                                : Messages.get("instances.target.other"))));
        boolean running = ui.isVersionRunning(instanceId);
        runtimeStatus.setText(Messages.get(running
                ? "instances.detail.running" : "instances.detail.idle"));
    }

    private void loadLaunchConfig() {
        if (instanceId == null) {
            return;
        }
        loadingConfig = true;
        try {
            InstanceLaunchProfile profile = loadProfile(instanceId);
            javaMode.selectToggle(profile.javaMode() == InstanceLaunchProfile.JavaMode.CUSTOM
                    ? javaCustom : javaAuto);
            javaPathField.setText(profile.javaPath());
            memoryMode.selectToggle(profile.memoryMode() == InstanceLaunchProfile.MemoryMode.CUSTOM
                    ? memoryCustom : memoryAuto);
            memoryField.setText(profile.maxMemoryMb() == ECLConfig.AUTO_MEMORY_MB
                    ? "" : Integer.toString(profile.maxMemoryMb()));
            jvmField.setText(TextUtil.formatCommandLine(profile.customJvmArguments()));
            configStatus.setText(Messages.get("instances.config.loaded"));
        } catch (IOException error) {
            configStatus.setText(Messages.format("instances.config.loadFailed", ui.cleanMessage(error)));
        } finally {
            loadingConfig = false;
            updateJavaRowState();
            updateMemoryRowState();
        }
    }

    private void updateJavaRowState() {
        boolean custom = javaCustom.isSelected();
        javaPathField.setDisable(!custom);
    }

    private void updateMemoryRowState() {
        memoryField.setDisable(!memoryCustom.isSelected());
    }

    private void chooseJavaExecutable() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("instances.java.prompt"));
        File initial = ui.prepareChooserDir(javaPathField.getText());
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "java.exe", "*.exe"));
        File selected = chooser.showOpenDialog(ui.primaryStage);
        if (selected != null) {
            javaPathField.setText(selected.getAbsolutePath());
        }
    }

    private void saveLaunchConfig() {
        if (instanceId == null || loadingConfig) {
            return;
        }
        String javaPath = javaPathField.getText() == null ? "" : javaPathField.getText().trim();
        if (javaCustom.isSelected()) {
            if (javaPath.isBlank()) {
                configStatus.setText(Messages.get("instances.config.javaRequired"));
                return;
            }
            if (!JavaRuntimeUtil.isUsableJavaPath(javaPath)) {
                configStatus.setText(Messages.get("instances.config.javaInvalid"));
                return;
            }
            javaPath = JavaRuntimeUtil.resolveJavaExecutable(javaPath);
        } else {
            javaPath = "";
        }
        int memoryMb;
        try {
            memoryMb = memoryAuto.isSelected() ? ECLConfig.AUTO_MEMORY_MB
                    : ui.parseMemorySetting(memoryField.getText());
            if (memoryCustom.isSelected() && memoryMb == ECLConfig.AUTO_MEMORY_MB) {
                throw new IllegalArgumentException(Messages.get("status.memoryInvalid"));
            }
        } catch (IllegalArgumentException memoryError) {
            configStatus.setText(memoryError.getMessage());
            return;
        }
        List<String> arguments;
        try {
            arguments = JvmArgumentPolicy.requireSafe(TextUtil.parseCommandLine(jvmField.getText()));
        } catch (IllegalArgumentException jvmError) {
            configStatus.setText(Messages.format("instances.config.jvmInvalid", jvmError.getMessage()));
            return;
        }
        try {
            InstanceLaunchProfile current = loadProfile(instanceId);
            InstanceLaunchProfile updated = new InstanceLaunchProfile(
                    current.schemaVersion(),
                    javaPath.isEmpty() ? InstanceLaunchProfile.JavaMode.AUTO
                            : InstanceLaunchProfile.JavaMode.CUSTOM,
                    javaPath,
                    current.performancePreset(),
                    memoryMb == ECLConfig.AUTO_MEMORY_MB ? InstanceLaunchProfile.MemoryMode.AUTO
                            : InstanceLaunchProfile.MemoryMode.CUSTOM,
                    memoryMb,
                    current.generatedJvmOptions(),
                    arguments,
                    current.autoRepair(),
                    current.backupPolicyId());
            ui.controller.instanceLaunchProfiles().save(instanceRootPath(instanceId), updated);
            // The launch path re-reads the profile each time, so no global state to refresh here.
            ui.updateRuntimeSummary();
            configStatus.setText(Messages.get("instances.config.saved"));
            ui.setStatus(Messages.get("instances.config.saved"), Messages.get("instances.config.scope"));
        } catch (IOException error) {
            configStatus.setText(Messages.format("instances.config.saveFailed", ui.cleanMessage(error)));
        }
    }

    private void rebuildMaintenance() {
        maintenanceActions.getChildren().setAll(maintenanceHint);
        VersionManager.LocalVersionProfile profile = localProfile(instanceId);
        boolean modded = profile != null && !profile.loader().isBlank();
        boolean isTarget = instanceId.equals(ui.getSelectedVersion());
        boolean running = ui.isVersionRunning(instanceId);

        maintenanceHint.setText(Messages.get(isTarget
                ? "instances.maintenance.targetHint" : "instances.maintenance.otherHint"));

        Button upgradeNow = ui.instanceUpdates.createButtonFor(instanceId);
        upgradeNow.setDisable(!modded || running);
        maintenanceActions.getChildren().add(upgradeNow);

        Button manageMods = ui.createActionButton(Messages.get("home.quickMods"),
                "secondary-button", () -> runTargetAction(
                        () -> ui.openDownloadSection(DownloadSection.CONTENT), false));
        manageMods.setId("instance-manage-mods");
        manageMods.setDisable(!isTarget);
        maintenanceActions.getChildren().add(manageMods);

        Button exportPack = ui.createActionButton(Messages.get("instances.maintenance.export"),
                "secondary-button", this::exportInstance);
        exportPack.setDisable(running);
        maintenanceActions.getChildren().add(exportPack);

        Button reinstall = ui.createActionButton(Messages.get("instances.maintenance.reinstall"),
                "ghost-button", () -> runTargetAction(ui.versionActions::reinstallSelectedVersion, true));
        reinstall.setId("instance-reinstall");
        reinstall.setDisable(!isTarget || running);
        maintenanceActions.getChildren().add(reinstall);

        Button delete = ui.createActionButton(Messages.get("instances.maintenance.delete"),
                "ghost-button", () -> runTargetAction(ui.versionActions::deleteSelectedVersion, true));
        delete.setId("instance-delete");
        delete.setDisable(!isTarget || running);
        delete.getStyleClass().add("danger-button");
        maintenanceActions.getChildren().add(delete);
    }

    private void runTargetAction(Runnable action, boolean requiresStoppedGame) {
        if (instanceId == null || !instanceId.equals(ui.getSelectedVersion())
                || requiresStoppedGame && ui.isVersionRunning(instanceId)) {
            refreshTargetState();
            return;
        }
        action.run();
    }

    private void exportInstance() {
        if (instanceId == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("instances.maintenance.export"));
        chooser.setInitialFileName(instanceId + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ECL package", "*.zip"));
        File target = chooser.showSaveDialog(ui.primaryStage);
        if (target == null) {
            return;
        }
        String minecraftVersion = minecraftVersionOf(instanceId);
        Path instanceRoot = instanceRootPath(instanceId);
        Path runDirectory = ui.resolveVersionGameDir(instanceId).toPath();
        Path output = target.toPath();
        ui.setStatus(Messages.get("instances.maintenance.exporting"),
                ui.versionManager.getVersionDisplayName(instanceId));
        ui.runAsync("ecl-export-instance", () -> {
            try {
                ui.controller.packService().exportInstance(
                        instanceRoot, runDirectory, minecraftVersion, PackFormat.ECL, output);
                Platform.runLater(() -> ui.setStatus(Messages.get("instances.maintenance.exported"),
                        output.toString()));
            } catch (IOException error) {
                Platform.runLater(() -> ui.setStatus(Messages.get("instances.maintenance.exportFailed"),
                        ui.cleanMessage(error)));
            }
        });
    }

    private String minecraftVersionOf(String profileId) {
        VersionManager.LocalVersionProfile profile = localProfile(profileId);
        return profile == null ? profileId : profile.minecraftVersion();
    }

    private VersionManager.LocalVersionProfile localProfile(String profileId) {
        return ui.versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .findFirst()
                .orElse(null);
    }

    private Path instanceRootPath(String profileId) {
        return ui.resolveVersionInstanceRoot(profileId).toPath();
    }

    private InstanceLaunchProfile loadProfile(String profileId) throws IOException {
        return ui.controller.instanceLaunchProfiles().load(instanceRootPath(profileId));
    }
}
