package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Search-result renderer with asynchronous icons and translated summaries. */
final class ModProjectCell extends ListCell<ModProject> {
    private final ModBrowserViewModel viewModel;

    ModProjectCell(ModBrowserViewModel viewModel) {
        this.viewModel = viewModel;
    }

    @Override
    protected void updateItem(ModProject project, boolean empty) {
        super.updateItem(project, empty);
        if (empty || project == null) {
            setGraphic(null);
            setText(null);
            return;
        }
        ImageView icon = new ImageView(RemoteImageLoader.loadingPlaceholder());
        icon.setFitWidth(42);
        icon.setFitHeight(42);
        icon.setPreserveRatio(true);
        if (project.iconUrl() != null) {
            RemoteImageLoader.load(project.iconUrl()).thenAccept(image ->
                    Platform.runLater(() -> {
                        if (getItem() == project) {
                            icon.setImage(image);
                        }
                    }));
        } else {
            icon.setImage(RemoteImageLoader.brokenPlaceholder());
        }
        Label title = new Label(project.title());
        title.getStyleClass().add("mod-item-title");
        Label description = new Label("正在翻译简介…");
        description.getStyleClass().add("status-detail");
        description.setWrapText(true);
        description.setMaxWidth(270);
        ChineseDescriptionService.translate(project.description()).thenAccept(translated ->
                Platform.runLater(() -> {
                    if (getItem() == project) {
                        description.setText(translated == null || translated.isBlank()
                                ? project.description() : translated);
                    }
                }));
        boolean installed = viewModel.installedMods().stream()
                .anyMatch(mod -> project.projectId().equals(mod.projectId()));
        String badges = project.author() + " · ↓ " + project.downloads()
                + (installed ? " · 已安装" : "")
                + (viewModel.hasUpdate(project.projectId()) ? " · 可更新" : "");
        Label meta = new Label(badges);
        meta.getStyleClass().add("mod-item-meta");
        VBox text = new VBox(3, title, description, meta);
        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.CENTER_LEFT);
        setGraphic(row);
    }
}
