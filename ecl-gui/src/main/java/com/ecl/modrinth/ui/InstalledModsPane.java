package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
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
        Button updateAllButton = ModUiControls.button("全部更新", "primary-button");
        updateAllButton.setOnAction(event -> updateAll.run());
        Button historyButton = ModUiControls.button("历史版本", "ghost-button");
        historyButton.setOnAction(event -> history.run());
        Button project = ModUiControls.button("项目页面", "ghost-button");
        project.setOnAction(event -> openProject.run());
        Button file = ModUiControls.button("文件位置", "ghost-button");
        file.setOnAction(event -> openFile.run());
        Button importButton = ModUiControls.button("导入 JAR", "secondary-button");
        importButton.setOnAction(event -> importJar.run());

        HBox rowOne = new HBox(8, scan, enable, disable, uninstallButton, importButton);
        HBox rowTwo = new HBox(8, updates, updateSelectedButton, updateAllButton,
                historyButton, project, file);
        getChildren().setAll(list, rowOne, rowTwo);
        setSpacing(10);
        setPadding(new Insets(10, 0, 0, 0));
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
}
