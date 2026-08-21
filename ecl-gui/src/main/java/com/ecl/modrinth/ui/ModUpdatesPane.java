package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** Available-update list, selection and update action toolbar. */
final class ModUpdatesPane extends VBox {
    private final ModBrowserViewModel viewModel;
    private final ListView<ModUpdate> list = new ListView<>();
    private final Button checkButton;
    private final Button updateSelectedButton;
    private final Button updateAllButton;

    ModUpdatesPane(ModBrowserViewModel viewModel, Runnable checkUpdates,
                   Consumer<List<ModUpdate>> runUpdates) {
        this.viewModel = viewModel;
        list.setItems(FXCollections.observableArrayList());
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(ignored -> new ModUpdateCell());
        list.setPlaceholder(new Label(
                "没有可用的 Mod 更新。点击“检查更新”重新扫描。"));
        VBox.setVgrow(list, Priority.ALWAYS);

        checkButton = ModUiControls.button("检查更新", "secondary-button");
        checkButton.setOnAction(event -> checkUpdates.run());
        updateSelectedButton = ModUiControls.button("更新选中", "primary-button");
        updateSelectedButton.setOnAction(event -> runUpdates.accept(selectedUpdates()));
        updateAllButton = ModUiControls.button("一键更新全部", "primary-button");
        updateAllButton.setOnAction(event ->
                runUpdates.accept(List.copyOf(viewModel.availableUpdates())));
        HBox actions = new HBox(8, checkButton, updateSelectedButton, updateAllButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        list.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<ModUpdate>) change -> refreshButtons(false));
        getChildren().setAll(list, actions);
        setSpacing(10);
        setPadding(new Insets(10, 0, 0, 0));
        refreshButtons(false);
    }

    List<ModUpdate> selectedUpdates() {
        return List.copyOf(list.getSelectionModel().getSelectedItems());
    }

    void refreshItems() {
        list.setItems(FXCollections.observableArrayList(viewModel.availableUpdates()));
    }

    void refreshButtons(boolean batchRunning) {
        boolean busy = batchRunning || viewModel.loadingProperty().get();
        checkButton.setDisable(busy);
        updateSelectedButton.setDisable(busy
                || list.getSelectionModel().getSelectedItems().isEmpty());
        updateAllButton.setDisable(busy || viewModel.availableUpdates().isEmpty());
    }
}
