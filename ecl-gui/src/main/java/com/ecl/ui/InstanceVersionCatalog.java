package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Three-column Minecraft version catalog used by the instance download workspace. */
final class InstanceVersionCatalog extends VBox {
    private final LauncherUI ui;
    private final Consumer<String> versionSelectionHandler;
    private final ListView<String> releases = createList();
    private final ListView<String> snapshots = createList();
    private final ListView<String> aprilFools = createList();
    private final Label releaseCount = countLabel();
    private final Label snapshotCount = countLabel();
    private final Label aprilFoolsCount = countLabel();
    private final Label status = new Label(Messages.get("instance.catalog.loading"));
    private final AtomicLong generation = new AtomicLong();
    private final TitledPane releaseGroup;
    private final TitledPane snapshotGroup;
    private final TitledPane aprilFoolsGroup;
    private boolean synchronizingSelection;

    InstanceVersionCatalog(LauncherUI ui, Consumer<String> versionSelectionHandler) {
        this.ui = ui;
        this.versionSelectionHandler = versionSelectionHandler;
        getStyleClass().add("instance-version-catalog");
        setSpacing(12);
        releaseGroup = createGroup(
                Messages.get("instance.catalog.releases"), releaseCount, releases, true);
        snapshotGroup = createGroup(
                Messages.get("instance.catalog.snapshots"), snapshotCount, snapshots, false);
        aprilFoolsGroup = createGroup(
                Messages.get("instance.catalog.aprilFools"), aprilFoolsCount, aprilFools, false);
        VBox groups = new VBox(10, releaseGroup, snapshotGroup, aprilFoolsGroup);
        groups.getStyleClass().add("instance-version-groups");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        getChildren().addAll(groups, status);
        installSelection(releases);
        installSelection(snapshots);
        installSelection(aprilFools);
        ui.versionCombo.valueProperty().addListener((obs, oldValue, newValue) -> selectMatching(newValue));
        load(!Boolean.getBoolean("ecl.snapshot"));
    }

    private TitledPane createGroup(String titleText, Label count,
                                   ListView<String> list, boolean expanded) {
        Label title = new Label(titleText);
        title.getStyleClass().add("instance-version-column-title");
        HBox header = new HBox(8, title, count);
        header.setAlignment(Pos.CENTER_LEFT);
        TitledPane group = new TitledPane();
        group.setGraphic(header);
        group.setContent(list);
        group.setExpanded(expanded);
        group.setAnimated(false);
        group.setMaxWidth(Double.MAX_VALUE);
        group.getStyleClass().add("instance-version-group");
        return group;
    }

    private void load(boolean refreshManifest) {
        long request = generation.incrementAndGet();
        setListsDisabled(true);
        status.setText(refreshManifest
                ? Messages.get("instance.catalog.refreshing") : Messages.get("instance.catalog.loading"));
        ui.runAsync("ecl-version-catalog", () -> {
            try {
                if (refreshManifest) {
                    ui.versionManager.refresh();
                }
                List<String> releaseItems = ui.versionManager.getReleaseVersions();
                List<String> aprilFoolsItems = ui.versionManager.getAprilFoolsVersions();
                Set<String> aprilFoolsIds = new HashSet<>(aprilFoolsItems);
                List<String> snapshotItems = ui.versionManager.getPreviewVersions().stream()
                        .filter(version -> !aprilFoolsIds.contains(version))
                        .toList();
                Platform.runLater(() -> applyVersions(
                        request, releaseItems, snapshotItems, aprilFoolsItems));
            } catch (Exception error) {
                Platform.runLater(() -> showFailure(request, error));
            }
        });
    }

    private void applyVersions(long request, List<String> releaseItems,
                               List<String> snapshotItems, List<String> aprilFoolsItems) {
        if (request != generation.get()) {
            return;
        }
        releases.getItems().setAll(releaseItems);
        snapshots.getItems().setAll(snapshotItems);
        aprilFools.getItems().setAll(aprilFoolsItems);
        releaseCount.setText(Integer.toString(releaseItems.size()));
        snapshotCount.setText(Integer.toString(snapshotItems.size()));
        aprilFoolsCount.setText(Integer.toString(aprilFoolsItems.size()));
        setListsDisabled(false);
        status.setText(Messages.format("instance.catalog.ready",
                releaseItems.size(), snapshotItems.size(), aprilFoolsItems.size()));
        selectMatching(ui.getSelectedVersion());
    }

    private void showFailure(long request, Exception error) {
        if (request != generation.get()) {
            return;
        }
        setListsDisabled(false);
        status.setText(Messages.format("instance.catalog.failed", ui.cleanMessage(error)));
    }

    private void installSelection(ListView<String> list) {
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, version) -> {
            if (synchronizingSelection || version == null || version.isBlank()) {
                return;
            }
            synchronizingSelection = true;
            for (ListView<String> candidate : List.of(releases, snapshots, aprilFools)) {
                if (candidate != list) {
                    candidate.getSelectionModel().clearSelection();
                }
            }
            ui.versionCombo.setValue(version);
            status.setText(Messages.format("instance.catalog.selected", version));
            synchronizingSelection = false;
            if (versionSelectionHandler != null) {
                versionSelectionHandler.accept(version);
            }
        });
    }

    private void selectMatching(String version) {
        if (synchronizingSelection || version == null) {
            return;
        }
        synchronizingSelection = true;
        for (ListView<String> list : List.of(releases, snapshots, aprilFools)) {
            int index = list.getItems().indexOf(version);
            if (index >= 0) {
                groupFor(list).setExpanded(true);
                list.getSelectionModel().select(index);
                list.scrollTo(index);
            } else {
                list.getSelectionModel().clearSelection();
            }
        }
        synchronizingSelection = false;
    }

    private TitledPane groupFor(ListView<String> list) {
        if (list == snapshots) {
            return snapshotGroup;
        }
        if (list == aprilFools) {
            return aprilFoolsGroup;
        }
        return releaseGroup;
    }

    private void setListsDisabled(boolean disabled) {
        releases.setDisable(disabled);
        snapshots.setDisable(disabled);
        aprilFools.setDisable(disabled);
    }

    private ListView<String> createList() {
        ListView<String> list = new ListView<>();
        list.getStyleClass().add("instance-version-list");
        list.setPrefHeight(220);
        list.setPlaceholder(new Label(Messages.get("instance.catalog.empty")));
        list.setCellFactory(view -> new VersionCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        return list;
    }

    private static Label countLabel() {
        Label label = new Label("0");
        label.getStyleClass().add("instance-version-count");
        return label;
    }

    private final class VersionCell extends ListCell<String> {
        @Override
        protected void updateItem(String version, boolean empty) {
            super.updateItem(version, empty);
            if (empty || version == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(version);
            name.getStyleClass().add("instance-version-id");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            boolean installed = ui.versionManager.isVersionDownloaded(version);
            Label state = new Label(Messages.get(installed
                    ? "instance.catalog.installed" : "instance.catalog.available"));
            state.getStyleClass().add(installed
                    ? "instance-version-installed" : "instance-version-available");
            HBox row = new HBox(8, name, spacer, state);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
