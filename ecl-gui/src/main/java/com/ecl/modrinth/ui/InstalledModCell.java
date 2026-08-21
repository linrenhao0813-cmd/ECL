package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Renderer for installed mod state, provenance, health and update availability. */
final class InstalledModCell extends ListCell<InstalledMod> {
    private final ModBrowserViewModel viewModel;

    InstalledModCell(ModBrowserViewModel viewModel) {
        this.viewModel = viewModel;
    }

    @Override
    protected void updateItem(InstalledMod mod, boolean empty) {
        super.updateItem(mod, empty);
        if (empty || mod == null) {
            setGraphic(null);
            setText(null);
            return;
        }
        Label state = new Label(mod.enabled() ? "ON" : "OFF");
        state.getStyleClass().add(mod.enabled() ? "mod-enabled" : "mod-disabled");
        Label title = new Label(mod.displayName());
        title.getStyleClass().add("mod-item-title");
        String detail = (mod.versionNumber().isBlank() ? mod.fileName() : mod.versionNumber())
                + " · " + mod.versionType()
                + " · " + ModUiFormatter.formatBytes(mod.fileSize())
                + (mod.dependency() ? " · 依赖" : "")
                + (mod.requiredByProjectId().isBlank()
                ? "" : " · 被 " + mod.requiredByProjectId() + " 依赖")
                + (mod.installedAt() == null
                ? "" : " · " + mod.installedAt().toString().substring(0, 10))
                + (mod.projectId().startsWith("local:")
                ? " · 本地/未知来源" : " · 在线来源")
                + (viewModel.healthMessage(mod.projectId()).isBlank()
                ? "" : " · " + viewModel.healthMessage(mod.projectId()))
                + (viewModel.hasUpdate(mod.projectId()) ? " · 可更新" : "");
        Label meta = new Label(detail);
        meta.getStyleClass().add("status-detail");
        VBox text = new VBox(3, title, meta);
        HBox row = new HBox(10, state, text);
        row.setAlignment(Pos.CENTER_LEFT);
        setGraphic(row);
    }
}
