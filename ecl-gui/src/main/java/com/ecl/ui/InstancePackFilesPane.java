package com.ecl.ui;

import com.ecl.game.InstancePackService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Local pack files for the launch target captured when this tab was opened. */
final class InstancePackFilesPane extends VBox {
    private final LauncherUI ui;
    private final String profile;
    private final String folder;
    private final ListView<Path> files = new ListView<>();
    private final Label status = new Label();
    private final FlowPane actions = new FlowPane(8, 8);
    private final Label count = new Label();
    private InstancePackService service;

    InstancePackFilesPane(LauncherUI ui, String folder) {
        super(12);
        this.ui = ui;
        this.folder = folder;
        profile = ui.getSelectedVersion();
        setId("instance-pack-files");
        Label hint = new Label(GuiMessages.get("instanceContent.packsHint"));
        hint.getStyleClass().add("section-subtitle");
        hint.setWrapText(true);
        status.setWrapText(true);
        status.getStyleClass().add("section-subtitle");
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());
        files.getStyleClass().add("pack-file-list");
        files.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Path value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.getFileName().toString());
            }
        });
        files.setPlaceholder(new Label(GuiMessages.get("instanceContent.emptyPacks")));
        Button refresh = button("refresh", this::refresh);
        Button add = button("import", this::importPack);
        add.getStyleClass().remove("secondary-button");
        add.getStyleClass().add("primary-button");
        Button remove = button("remove", this::removePack);
        remove.getStyleClass().remove("secondary-button");
        remove.getStyleClass().add("danger-button");
        remove.disableProperty().bind(files.getSelectionModel().selectedItemProperty().isNull());
        Button open = button("openFolder", () -> ui.openLocalFolder(
                ui.resolveVersionGameDir(profile).toPath().resolve(folder).toFile(), GuiMessages.get("instanceContent.openFolder")));
        actions.getChildren().addAll(add, refresh, open, remove);
        actions.getStyleClass().add("pack-file-actions");
        Label title = new Label(GuiMessages.get("instanceContent." + ("shaderpacks".equals(folder) ? "shaders" : "resources")));
        title.getStyleClass().add("section-title");
        count.getStyleClass().add("management-count-badge");
        count.setText(GuiMessages.get("instanceContent.packCount", 0));
        HBox titleRow = new HBox(12, title, count);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(12, titleRow, hint, actions);
        header.getStyleClass().add("pack-management-header");
        getChildren().addAll(header, files, status);
        VBox.setVgrow(files, Priority.ALWAYS);
        if (profile == null || profile.isBlank()) {
            actions.setDisable(true);
            status.setText(GuiMessages.get("instanceContent.noInstance"));
        } else {
            service = new InstancePackService(ui.resolveVersionGameDir(profile).toPath(), folder);
            Label location = new Label(ui.resolveVersionGameDir(profile).toPath().resolve(folder).toString());
            location.getStyleClass().add("pack-directory-path");
            location.setMaxWidth(Double.MAX_VALUE);
            location.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            location.setTooltip(new Tooltip(location.getText()));
            location.setAccessibleText(GuiMessages.get("instanceContent.openFolder") + ": " + location.getText());
            header.getChildren().add(2, location);
            refresh();
        }
    }

    private Button button(String key, Runnable action) {
        Button button = ui.createActionButton(GuiMessages.get("instanceContent." + key), "secondary-button", action);
        button.setId("pack-" + folder + "-" + key);
        return button;
    }

    private void refresh() {
        run(() -> {
            var entries = service.list();
            Platform.runLater(() -> showEntries(entries));
        });
    }

    private void importPack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(GuiMessages.get("instanceContent.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        File source = chooser.showOpenDialog(getScene().getWindow());
        if (source != null) mutate(() -> service.importPack(source.toPath()));
    }

    private void removePack() {
        Path selected = files.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                GuiMessages.get("instanceContent.removeConfirm", selected.getFileName()), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(getScene().getWindow());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            mutate(() -> service.remove(selected.getFileName().toString()));
        }
    }

    private void mutate(IoAction action) {
        if (ui.isVersionRunning(profile) || (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profile))) {
            status.setText(GuiMessages.get("instanceContent.running"));
            return;
        }
        run(() -> {
            action.run();
            var entries = service.list();
            Platform.runLater(() -> showEntries(entries));
        });
    }

    private void showEntries(java.util.List<Path> entries) {
        files.getItems().setAll(entries);
        count.setText(GuiMessages.get("instanceContent.packCount", entries.size()));
    }

    private void run(IoAction action) {
        actions.setDisable(true);
        status.setText(GuiMessages.get("instanceContent.working"));
        ui.runAsync("ecl-pack-files", () -> {
            String result;
            try {
                action.run();
                result = "";
            } catch (IOException | RuntimeException error) {
                result = ui.cleanMessage(error);
            }
            String message = result;
            Platform.runLater(() -> {
                actions.setDisable(false);
                status.setText(message);
            });
        });
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
