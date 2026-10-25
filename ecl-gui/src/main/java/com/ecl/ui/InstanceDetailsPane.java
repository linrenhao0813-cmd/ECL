package com.ecl.ui;

import com.ecl.util.FileUtil;
import com.ecl.launcher.VersionManager;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
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

/** Overview and display settings for the viewed instance; selection does not change the launch target. */
final class InstanceDetailsPane extends VBox {
    private final LauncherUI ui;
    private final Runnable onDisplayChanged;
    private String instanceId;
    private boolean deleting;
    private List<Object> savedDisplay = List.of();

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
    private final Button openFolderButton;
    private final Label emptyHint = new Label(Messages.get("instances.detail.empty"));
    private final Button launchSettingsButton;
    private final Button checkRepairButton;
    private final InstanceCheckRepairWorkflow checkRepairWorkflow;
    private final Button restoreUpdateButton;
    private final InstanceUpgradeRecoveryWorkflow upgradeRecoveryWorkflow;
    private final MenuButton instanceMenu = new MenuButton();
    private final MenuItem createShortcutButton;
    private final MenuItem createStartMenuShortcutItem;
    private final MenuItem deleteInstanceButton;

    private final VBox overviewRows = new VBox(8);
    private final Label runtimeStatus = new Label();

    private final TabPane tabs = new TabPane();
    private final VBox body = new VBox(14);

    InstanceDetailsPane(LauncherUI ui, Runnable onDisplayChanged) {
        this.ui = ui;
        this.onDisplayChanged = onDisplayChanged;
        checkRepairWorkflow = new InstanceCheckRepairWorkflow(ui);
        upgradeRecoveryWorkflow = new InstanceUpgradeRecoveryWorkflow(ui);
        getStyleClass().add("instance-details");
        setSpacing(14);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        title.getStyleClass().add("section-title");
        meta.getStyleClass().add("section-subtitle");
        meta.setWrapText(true);
        openFolderButton = ui.createActionButton(Messages.get("local.instances.openFolder"),
                "ghost-button", this::openInstanceFolder);
        launchSettingsButton = ui.createActionButton(Messages.get("instances.launch.button"),
                "secondary-button", this::openLaunchSettings);
        launchSettingsButton.setId("instance-launch-settings");
        checkRepairButton = ui.createActionButton(Messages.get("instanceRepair.button"),
                "secondary-button", this::checkAndRepair);
        checkRepairButton.setId("instance-check-repair");
        checkRepairButton.setAccessibleText(Messages.get("instanceRepair.button"));
        restoreUpdateButton = ui.createActionButton(Messages.get("instanceRecovery.button"),
                "ghost-button", this::restoreLastUpdate);
        restoreUpdateButton.setId("instance-restore-last-update");
        restoreUpdateButton.setAccessibleText(Messages.get("instanceRecovery.button"));
        createShortcutButton = new MenuItem(Messages.get("instances.shortcut.desktop"));
        createShortcutButton.setId("instance-create-shortcut");
        createShortcutButton.setOnAction(event -> createShortcut(false));
        createStartMenuShortcutItem = new MenuItem(Messages.get("instances.shortcut.startMenu"));
        createStartMenuShortcutItem.setId("instance-create-start-menu-shortcut");
        createStartMenuShortcutItem.setOnAction(event -> createShortcut(true));
        deleteInstanceButton = new MenuItem(Messages.get("instances.delete.button"));
        deleteInstanceButton.setId("instance-delete");
        deleteInstanceButton.setOnAction(event -> deleteInstance());
        instanceMenu.getItems().addAll(createShortcutButton, createStartMenuShortcutItem,
                new SeparatorMenuItem(), deleteInstanceButton);
        instanceMenu.setText(Messages.get("instances.moreActions"));
        instanceMenu.setId("instance-more-actions");
        instanceMenu.setAccessibleText(Messages.get("instances.moreActions"));
        instanceMenu.getStyleClass().add("instance-more-actions");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(10, new VBox(3, title, meta), headerSpacer,
                launchSettingsButton, checkRepairButton, restoreUpdateButton,
                instanceMenu, openFolderButton);
        header.setAlignment(Pos.CENTER_LEFT);

        ui.applyFieldStyle(displayNameField);
        displayNameField.setId("instance-display-name");
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
                ui.createSurface(Messages.get("instances.overview.title"),
                        Messages.get("instances.detail.meta2"), overviewRows, runtimeStatus),
                ui.createSurface(Messages.get("instances.display.title"),
                        Messages.get("instances.display.subtitle"), displayBox));

        tabs.getTabs().add(new Tab(Messages.get("instances.tab.overview"), overviewTab));
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
        setInstance(null);
    }

    void setInstance(String profileId) {
        instanceId = profileId == null || profileId.isBlank() ? null : profileId;
        boolean present = instanceId != null;
        emptyHint.setVisible(!present);
        emptyHint.setManaged(!present);
        body.setVisible(present);
        body.setManaged(present);
        refreshActionButtons();
        if (!present) {
            return;
        }
        loadDisplaySettings();
        refresh();
    }

    String instanceId() {
        return instanceId;
    }

    TabPane detailTabs() {
        return tabs;
    }

    boolean hasUnsavedChanges() {
        return instanceId != null && !savedDisplay.equals(displayValues());
    }

    boolean savePendingChanges() {
        if (hasUnsavedChanges()) saveDisplay();
        return !hasUnsavedChanges();
    }
    private List<Object> displayValues() {
        return List.of(displayNameField.getText(), favoriteBox.isSelected(), coverPath);
    }

    /** Refreshes status without overwriting unsaved display settings. */
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
        updateOverviewRows(minecraftVersion, loader);
        refreshActionButtons();
    }

    private void loadDisplaySettings() {
        com.ecl.game.InstanceDisplayMetadata display = ui.instanceDisplay.get(instanceId);
        displayNameField.setPromptText(ui.versionManager.getVersionDisplayName(instanceId));
        displayNameField.setText(display.displayName());
        favoriteBox.setSelected(display.favorite());
        coverPath = display.coverImage();
        displayStatus.setText("");
        updateCoverPreview();
        savedDisplay = displayValues();
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
            savedDisplay = displayValues();
            displayStatus.setText(Messages.get("instances.display.saved"));
        } catch (IllegalStateException error) {
            displayStatus.setText(error.getMessage());
        }
    }

    private void openInstanceFolder() {
        ui.openLocalFolder(ui.resolveVersionGameDir(instanceId),
                Messages.get("local.instances.folderTitle"));
    }

    private void openLaunchSettings() {
        if (instanceId != null) {
            InstanceLaunchProfileDialog.show(ui, instanceId, launchSettingsButton, this::refresh);
        }
    }

    private void checkAndRepair() {
        if (instanceId != null) {
            checkRepairWorkflow.start(instanceId, this::refresh);
            refreshActionButtons();
        }
    }

    private void restoreLastUpdate() {
        if (instanceId != null) {
            upgradeRecoveryWorkflow.open(instanceId, this::refresh);
            refreshActionButtons();
        }
    }

    /** Shortcuts always describe the viewed instance, so it has to be the launch target. */
    private void createShortcut(boolean startMenu) {
        if (instanceId == null) {
            return;
        }
        if (!instanceId.equals(ui.getSelectedVersion())) {
            ui.setLaunchTarget(instanceId);
        }
        ui.createInstanceShortcut(startMenu);
    }

    private void deleteInstance() {
        if (instanceId == null || deleting) {
            return;
        }
        String profileId = instanceId;
        if (ui.isVersionRunning(profileId)) {
            ui.setStatus(Messages.get("instances.delete.runningTitle"),
                    Messages.get("instances.delete.running"));
            return;
        }
        if (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)) {
            ui.setStatus(Messages.get("instances.delete.busyTitle"),
                    Messages.get("instances.delete.busy"));
            return;
        }
        deleting = true;
        refreshActionButtons();
        if (Alert.AlertType.CONFIRMATION != confirmDeletion(profileId)) {
            deleting = false;
            refreshActionButtons();
            return;
        }
        ui.setStatus(Messages.get("instances.delete.title"),
                Messages.format("instances.delete.progress", profileId));
        try {
            ui.runAsync("ecl-delete-instance", () -> {
                try {
                    deleteInstanceFiles(profileId);
                } finally {
                    Platform.runLater(() -> {
                        deleting = false;
                        refreshActionButtons();
                    });
                }
            });
        } catch (RuntimeException rejected) {
            deleting = false;
            refreshActionButtons();
            reportDeletionFailure(profileId, ui.cleanMessage(rejected));
        }
    }

    private Alert.AlertType confirmDeletion(String profileId) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.format("instances.delete.body", profileId),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(ui.primaryStage);
        confirm.setTitle(Messages.get("instances.delete.title"));
        confirm.setHeaderText(Messages.get("instances.delete.header"));
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
                ? Alert.AlertType.CONFIRMATION : Alert.AlertType.NONE;
    }

    /**
     * Deletes only the version directory. The versions root is the walk root, so the bounded
     * delete helper can never escape it, and both instance locks are held while it runs.
     */
    private void deleteInstanceFiles(String profileId) {
        Path versionsRoot = ui.getConfiguredGameRootDir().toPath().toAbsolutePath().normalize().resolve("versions");
        Path target = ui.resolveVersionInstanceRoot(profileId).toPath();
        try (InstanceOperationLease lock = InstanceOperationLease.tryAcquire(target)) {
            if (lock == null) {
                reportDeletionFailure(profileId, Messages.get("instances.delete.busy"));
                return;
            }
            Path runDirectory = ui.gameRepository().runDirectory(profileId);
            if (ui.isVersionRunning(profileId) || com.ecl.launch.GameProcessMarker.isRunning(runDirectory)
                    || ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)) {
                throw new IOException(Messages.get("instances.delete.busy"));
            }
            FileUtil.validateExistingAncestors(versionsRoot, target);
            VersionActions.deleteTreeWithin(versionsRoot, target, null);
            Platform.runLater(() -> {
                ui.controller.invalidateLaunchVersion(profileId);
                ui.versionManager.invalidateLocalVersionProfiles();
                if (profileId.equals(ui.getSelectedVersion())) {
                    ui.setLaunchTarget(null);
                }
                ui.versionActions.restoreVersionComboItems(null, true);
                ui.updateRuntimeSummary();
                setInstance(null);
                ui.setStatus(Messages.get("instances.delete.done"), profileId);
            });
        } catch (Exception error) {
            reportDeletionFailure(profileId, ui.cleanMessage(error));
        }
    }

    private void reportDeletionFailure(String profileId, String reason) {
        Platform.runLater(() -> ui.setStatus(Messages.get("instances.delete.failedTitle"),
                Messages.format("instances.delete.failed", reason)));
    }

    private void refreshActionButtons() {
        boolean present = instanceId != null;
        boolean busy = present && (checkRepairWorkflow.isBusy(instanceId)
                || upgradeRecoveryWorkflow.isBusy());
        launchSettingsButton.setDisable(!present);
        checkRepairButton.setDisable(!present || checkRepairWorkflow.isBusy(instanceId));
        restoreUpdateButton.setDisable(!present || upgradeRecoveryWorkflow.isBusy());
        instanceMenu.setDisable(!present);
        createShortcutButton.setDisable(!present);
        createStartMenuShortcutItem.setDisable(!present);
        deleteInstanceButton.setDisable(!present || busy || deleting || ui.isVersionRunning(instanceId));
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
                        ui.createStaticValueLabel(loader)));
        boolean running = ui.isVersionRunning(instanceId);
        runtimeStatus.setText(Messages.get(running
                ? "instances.detail.running" : "instances.detail.idle"));
    }

    private VersionManager.LocalVersionProfile localProfile(String profileId) {
        return ui.versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .findFirst()
                .orElse(null);
    }

}
