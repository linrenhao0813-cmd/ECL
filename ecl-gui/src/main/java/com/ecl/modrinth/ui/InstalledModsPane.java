package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import com.ecl.util.Messages;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** Installed-mod list and its direct action toolbar. */
final class InstalledModsPane extends VBox {
    private final ListView<InstalledMod> list = new ListView<>();

    InstalledModsPane(ModBrowserViewModel viewModel, Consumer<Boolean> setEnabled,
                      Runnable uninstall, Runnable checkUpdates, Runnable updateSelected,
                      Runnable updateAll, Runnable history, Runnable openProject,
                      Runnable openFile, Runnable importJar) {
        list.setItems(viewModel.installedMods());
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(ignored -> new InstalledModCell(viewModel));
        list.setPlaceholder(new Label(
                "尚未发现模组，点击“重新扫描”同步 mods 目录"));
        VBox.setVgrow(list, Priority.ALWAYS);

        Button scan = ModUiControls.button("重新扫描", "secondary-button");
        scan.setOnAction(event -> viewModel.rescan());
        Button enable = ModUiControls.button("启用", "ghost-button");
        enable.setOnAction(event -> setEnabled.accept(true));
        Button disable = ModUiControls.button("禁用", "ghost-button");
        disable.setOnAction(event -> setEnabled.accept(false));
        Button uninstallButton = ModUiControls.button("卸载", "danger-button");
        uninstallButton.setOnAction(event -> uninstall.run());
        Button updates = ModUiControls.button("检查更新", "secondary-button");
        updates.setOnAction(event -> checkUpdates.run());
        Button updateSelectedButton = ModUiControls.button("更新选中", "primary-button");
        updateSelectedButton.setOnAction(event -> updateSelected.run());
        Button updateAllButton = ModUiControls.button("全部更新", "secondary-button");
        updateAllButton.setOnAction(event -> updateAll.run());
        Button historyButton = ModUiControls.button("历史版本", "ghost-button");
        historyButton.setOnAction(event -> history.run());
        Button project = ModUiControls.button("项目页面", "ghost-button");
        project.setOnAction(event -> openProject.run());
        Button file = ModUiControls.button("文件位置", "ghost-button");
        file.setOnAction(event -> openFile.run());
        Button importButton = ModUiControls.button("导入 JAR", "secondary-button");
        importButton.setOnAction(event -> importJar.run());

        FlowPane toolbar = new FlowPane(12, 12,
                actionGroup("mods.actions.files", scan, importButton, file),
                actionGroup("mods.actions.selection", enable, disable, uninstallButton, historyButton, project),
                actionGroup("mods.actions.updates", updates, updateSelectedButton, updateAllButton));
        toolbar.getStyleClass().add("installed-mod-toolbar");
        getChildren().setAll(list, toolbar);
        setSpacing(10);
        setPadding(new Insets(10, 0, 0, 0));
    }

    private static VBox actionGroup(String key, Button... buttons) {
        Label caption = new Label(Messages.get(key));
        caption.getStyleClass().add("management-group-label");
        FlowPane controls = new FlowPane(8, 8, buttons);
        controls.setPrefWrapLength(300);
        VBox group = new VBox(10, caption, controls);
        group.getStyleClass().add("management-action-group");
        group.setPrefWidth(328);
        return group;
    }

    List<String> selectedIds() {
        return list.getSelectionModel().getSelectedItems().stream()
                .map(InstalledMod::projectId).distinct().toList();
    }

    List<InstalledMod> selectedItems() {
        return List.copyOf(list.getSelectionModel().getSelectedItems());
    }

    InstalledMod selectedItem() {
        return list.getSelectionModel().getSelectedItem();
    }

    void refresh() {
        list.refresh();
    }

    /** Renderer for installed mod state, provenance, health and update availability. */
    private static final class InstalledModCell extends ListCell<InstalledMod> {
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
}
