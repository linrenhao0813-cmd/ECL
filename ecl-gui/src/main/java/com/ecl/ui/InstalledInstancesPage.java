package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Instance manager: a list of launchable instances next to a detail workspace. Selecting a row only
 * changes what is being viewed; the launch target changes only through the explicit
 * "set as launch target" action. Narrow windows stack the two panes instead of shrinking them.
 */
final class InstalledInstancesPage extends VBox {
    private final LauncherUI ui;
    private final ListView<String> instances = new ListView<>();
    private final Label status = new Label(Messages.get("local.instances.loading"));
    private final Button refreshButton;
    private final Button installButton;
    private final InstanceDetailsPane details;
    private final VBox rightPane = new VBox(14);
    private final VBox listColumn;
    private final HBox wideLayout = new HBox(18);
    private final VBox narrowLayout = new VBox(14);
    private InstanceInstallWizard wizard;
    private Boolean compactMode;

    InstalledInstancesPage(LauncherUI ui) {
        this.ui = ui;
        this.details = new InstanceDetailsPane(ui, () -> instances.refresh());
        getStyleClass().add("launch-pane");
        setSpacing(18);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);

        Label title = new Label(Messages.get("local.instances.title"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(Messages.get("local.instances.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);

        instances.setId("installed-instance-list");
        instances.getStyleClass().add("instance-version-list");
        instances.setPlaceholder(new Label(Messages.get("local.instances.empty")));
        instances.setCellFactory(ignored -> createInstanceCell());
        VBox.setVgrow(instances, Priority.ALWAYS);

        status.getStyleClass().add("status-detail");
        status.setWrapText(true);

        refreshButton = ui.createActionButton(
                Messages.get("local.instances.refresh"), "secondary-button", this::refreshInstances);
        refreshButton.setId("instance-list-refresh");
        installButton = ui.createActionButton(
                Messages.get("instances.installNew"), "primary-button", this::showInstallWizard);
        installButton.setId("instance-install-new");

        VBox listActions = new VBox(8, installButton, refreshButton);
        listActions.setFillWidth(true);

        listColumn = new VBox(10);
        listColumn.getStyleClass().add("instance-list-column");
        listColumn.setMinWidth(260);
        listColumn.setPrefWidth(300);
        listColumn.setMaxWidth(360);
        listColumn.getChildren().addAll(instances, listActions, status);
        VBox.setVgrow(instances, Priority.ALWAYS);

        rightPane.setMinWidth(0);
        rightPane.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(rightPane, Priority.ALWAYS);
        rightPane.getChildren().setAll(details);

        wideLayout.getStyleClass().add("instance-workspace");
        wideLayout.setAlignment(Pos.TOP_LEFT);
        wideLayout.setMinWidth(0);
        HBox.setHgrow(rightPane, Priority.ALWAYS);
        narrowLayout.getStyleClass().addAll("instance-workspace", "instance-workspace-narrow");
        narrowLayout.setMinWidth(0);

        instances.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> selectInstance(selected));

        VBox heading = new VBox(6, title, subtitle);
        heading.getStyleClass().add("content-library-heading");
        getChildren().add(heading);
        applyCompact(false);
        refreshInstances();
    }

    /** Stacks the list above the details on narrow windows. */
    void setCompact(boolean compact) {
        applyCompact(compact);
    }

    private void applyCompact(boolean compact) {
        if (Objects.equals(compactMode, compact)) {
            return;
        }
        compactMode = compact;
        wideLayout.getChildren().clear();
        narrowLayout.getChildren().clear();
        Node workspace;
        if (compact) {
            // Cap the list so the detail workspace stays visible underneath it.
            listColumn.setMaxHeight(260);
            instances.setPrefHeight(200);
            narrowLayout.getChildren().addAll(listColumn, rightPane);
            workspace = narrowLayout;
        } else {
            listColumn.setMaxHeight(Double.MAX_VALUE);
            instances.setPrefHeight(420);
            wideLayout.getChildren().addAll(listColumn, rightPane);
            workspace = wideLayout;
        }
        if (getChildren().size() > 1) {
            getChildren().set(1, workspace);
        } else {
            getChildren().add(workspace);
        }
    }

    private ListCell<String> createInstanceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String instanceId, boolean empty) {
                super.updateItem(instanceId, empty);
                if (empty || instanceId == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                com.ecl.game.InstanceDisplayMetadata display = ui.instanceDisplay.get(instanceId);
                String fallback = ui.versionManager.getVersionDisplayName(instanceId);
                Label name = new Label(display.effectiveName(fallback));
                name.getStyleClass().add("instance-version-id");
                name.setWrapText(true);
                VBox box = new VBox(3, name);
                box.setMinWidth(0);
                if (instanceId.equals(ui.getSelectedVersion())) {
                    Label badge = new Label(Messages.get("instances.target.current"));
                    badge.getStyleClass().add("instance-target-badge-current");
                    box.getChildren().add(badge);
                }
                if (!display.displayName().isEmpty()) {
                    Label original = new Label(fallback);
                    original.getStyleClass().add("instance-version-available");
                    original.setWrapText(true);
                    box.getChildren().add(original);
                }
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                if (display.favorite()) {
                    Label star = new Label("★");
                    star.getStyleClass().add("instance-favorite");
                    star.setAccessibleText(Messages.get("instances.display.favorite"));
                    row.getChildren().add(star);
                }
                row.getChildren().add(box);
                javafx.scene.image.ImageView cover = InstanceCoverImages.thumbnail(display.coverImage());
                if (cover != null) {
                    row.getChildren().add(cover);
                }
                setGraphic(row);
                setText(null);
            }
        };
    }

    private void selectInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            details.setInstance(null);
            return;
        }
        ui.instanceSelection.setViewedInstance(instanceId);
        showDetailsPane();
        details.setInstance(instanceId);
    }

    /** Hosts the inline install wizard in the detail pane instead of navigating away. */
    private void showInstallWizard() {
        if (wizard == null) {
            wizard = new InstanceInstallWizard(ui, this::showDetailsPane, this::onInstanceInstalled);
        }
        rightPane.getChildren().setAll(wizard);
        ui.setStatus(Messages.get("instances.wizard.started"), Messages.get("instances.installNew"));
    }

    private void showDetailsPane() {
        rightPane.getChildren().setAll(details);
    }

    private void onInstanceInstalled(String profileId) {
        showDetailsPane();
        refreshInstances();
        if (profileId != null && !profileId.isBlank()) {
            ui.instanceSelection.setViewedInstance(profileId);
        }
    }

    private void refreshInstances() {
        String preferred = ui.getSelectedVersion();
        String viewed = ui.instanceSelection.viewedInstance();
        setBusy(true);
        status.setText(Messages.get("local.instances.loading"));
        ui.runAsync("ecl-local-instances", () -> {
            ui.versionManager.invalidateLocalVersionProfiles();
            List<String> installed = ui.gameRepository().installedInstanceDirectories();
            Platform.runLater(() -> applyInstances(installed, preferred, viewed));
        });
    }

    private void applyInstances(List<String> installed, String preferred, String viewed) {
        List<String> ordered = new ArrayList<>(installed);
        // Favourites float to the top, then keep the launcher's own ordering.
        ordered.sort(java.util.Comparator.comparingInt(
                (String profileId) -> ui.instanceDisplay.get(profileId).favorite() ? 0 : 1));
        instances.getItems().setAll(ordered);
        String selected = VersionActions.chooseInstalledVersion(installed,
                viewed != null && installed.contains(viewed) ? viewed : preferred);
        if (selected != null) {
            instances.getSelectionModel().select(selected);
            instances.scrollTo(selected);
        } else {
            instances.getSelectionModel().clearSelection();
            details.setInstance(null);
        }
        status.setText(installed.isEmpty()
                ? Messages.get("local.instances.none")
                : Messages.format("local.instances.ready", installed.size()));
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        instances.setDisable(busy);
        refreshButton.setDisable(busy);
        installButton.setDisable(busy);
    }
}
