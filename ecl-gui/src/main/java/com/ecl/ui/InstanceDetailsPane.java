package com.ecl.ui;

import com.ecl.launcher.VersionManager;
import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
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
import java.util.List;

/** Overview and display settings for the viewed instance; selection does not change the launch target. */
final class InstanceDetailsPane extends VBox {
    private final LauncherUI ui;
    private final Runnable onDisplayChanged;
    private String instanceId;
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

    private final VBox overviewRows = new VBox(8);
    private final Label runtimeStatus = new Label();

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
        openFolderButton = ui.createActionButton(Messages.get("local.instances.openFolder"),
                "ghost-button", this::openInstanceFolder);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(10, new VBox(3, title, meta), headerSpacer,
                openFolderButton);
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
        if (!present) {
            return;
        }
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

    /** Reloads overview and display settings for the current instance. */
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
        loadDisplaySettings();
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
