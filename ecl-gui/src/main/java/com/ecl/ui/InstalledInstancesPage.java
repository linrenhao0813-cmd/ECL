package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** Lists launchable instances already present in the configured .minecraft directory. */
final class InstalledInstancesPage extends VBox {
    private final LauncherUI ui;
    private final ListView<String> instances = new ListView<>();
    private final Label status = new Label(Messages.get("local.instances.loading"));
    private final Button useButton;
    private final Button openFolderButton;
    private final Button refreshButton;

    InstalledInstancesPage(LauncherUI ui) {
        this.ui = ui;
        getStyleClass().add("launch-pane");
        setSpacing(18);
        setPrefWidth(LauncherUI.LAUNCH_WIDTH);
        setMaxWidth(LauncherUI.LAUNCH_WIDTH);

        Label title = new Label(Messages.get("local.instances.title"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(Messages.get("local.instances.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        VBox heading = new VBox(6, title, subtitle);
        heading.getStyleClass().add("content-library-heading");

        instances.setId("installed-instance-list");
        instances.getStyleClass().add("instance-version-list");
        instances.setPlaceholder(new Label(Messages.get("local.instances.empty")));
        instances.setCellFactory(ignored -> createInstanceCell());
        instances.setPrefHeight(480);
        VBox.setVgrow(instances, Priority.ALWAYS);

        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        useButton = ui.createActionButton(
                Messages.get("local.instances.use"), "primary-button", this::useSelectedInstance);
        openFolderButton = ui.createActionButton(
                Messages.get("local.instances.openFolder"), "ghost-button", this::openSelectedFolder);
        refreshButton = ui.createActionButton(
                Messages.get("local.instances.refresh"), "secondary-button", this::refreshInstances);
        instances.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> updateActions(selected));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, status, spacer, refreshButton, openFolderButton, useButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);

        getChildren().addAll(heading, ui.createSurface(
                Messages.get("local.instances.cardTitle"),
                Messages.get("local.instances.cardSubtitle"), instances, actions));
        updateActions(null);
        refreshInstances();
    }

    private ListCell<String> createInstanceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String instanceId, boolean empty) {
                super.updateItem(instanceId, empty);
                setText(empty || instanceId == null
                        ? null : ui.versionManager.getVersionDisplayName(instanceId));
            }
        };
    }

    private void refreshInstances() {
        String preferred = ui.getSelectedVersion();
        setBusy(true);
        status.setText(Messages.get("local.instances.loading"));
        ui.runAsync("ecl-local-instances", () -> {
            ui.versionManager.invalidateLocalVersionProfiles();
            List<String> installed = ui.gameRepository().installedInstanceDirectories();
            Platform.runLater(() -> applyInstances(installed, preferred));
        });
    }

    private void applyInstances(List<String> installed, String preferred) {
        instances.getItems().setAll(installed);
        String selected = VersionActions.chooseInstalledVersion(installed, preferred);
        if (selected != null) {
            instances.getSelectionModel().select(selected);
            instances.scrollTo(selected);
        }
        status.setText(installed.isEmpty()
                ? Messages.get("local.instances.none")
                : Messages.format("local.instances.ready", installed.size()));
        setBusy(false);
        updateActions(instances.getSelectionModel().getSelectedItem());
    }

    private void useSelectedInstance() {
        String selected = instances.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isBlank()) return;
        ui.versionCombo.setValue(selected);
        ui.updateRuntimeSummary();
        ui.setActiveView(AppView.HOME);
        ui.setStatus(Messages.get("local.instances.selected"),
                ui.versionManager.getVersionDisplayName(selected));
    }

    private void openSelectedFolder() {
        String selected = instances.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isBlank()) return;
        ui.openLocalFolder(ui.resolveVersionGameDir(selected),
                Messages.get("local.instances.folderTitle"));
    }

    private void setBusy(boolean busy) {
        instances.setDisable(busy);
        refreshButton.setDisable(busy);
        if (busy) {
            useButton.setDisable(true);
            openFolderButton.setDisable(true);
        }
    }

    private void updateActions(String selected) {
        boolean unavailable = selected == null || selected.isBlank() || instances.isDisabled();
        useButton.setDisable(unavailable);
        openFolderButton.setDisable(unavailable);
    }
}
