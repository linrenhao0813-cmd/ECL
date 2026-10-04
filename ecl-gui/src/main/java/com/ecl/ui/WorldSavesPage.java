package com.ecl.ui;

import com.ecl.game.WorldSave;
import com.ecl.game.WorldSaveService;
import com.ecl.game.WorldSaveSettings;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Save browser grouped by Minecraft version and mod loader. */
final class WorldSavesPage extends VBox {
    private final LauncherUI ui;
    private final WorldSaveService service;
    private WorldSaveExplorer explorer;
    private final VBox detail = new VBox(12);
    private final Label detailTitle = new Label();
    private final Label detailMeta = new Label();
    private final Label detailPath = new Label();
    private final ComboBox<WorldSaveSettings.Difficulty> difficulty = new ComboBox<>();
    private final ComboBox<WorldSaveSettings.GameMode> gameMode = new ComboBox<>();
    private final CheckBox commands = new CheckBox();
    private Button openInstanceButton;
    private Button saveButton;
    private WorldSave selected;
    private final AtomicLong scanGeneration = new AtomicLong();

    WorldSavesPage(LauncherUI ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.service = new WorldSaveService(ui::isVersionRunning);
        setSpacing(18);
        setPadding(new Insets(2, 0, 24, 0));
        getStyleClass().addAll("launch-pane", "world-saves-page");
        setPrefWidth(LauncherUI.LAUNCH_WIDTH);
        setMaxWidth(LauncherUI.LAUNCH_WIDTH);
        HBox.setHgrow(this, Priority.ALWAYS);
        build();
        refresh();
    }

    private void build() {
        Label title = new Label(Messages.get("saves.title"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(GuiMessages.get("explorer.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        buildDetails();
        explorer = new WorldSaveExplorer(ui, detail, this::showDetails, this::refresh);
        VBox.setVgrow(explorer, Priority.ALWAYS);
        getChildren().addAll(new VBox(6, title, subtitle), explorer);
    }

    private void buildDetails() {
        detail.getStyleClass().addAll("surface", "world-save-detail");
        detail.setMinWidth(0);
        detail.setMaxWidth(Double.MAX_VALUE);
        detailTitle.getStyleClass().add("section-title");
        detailMeta.getStyleClass().add("section-subtitle");
        detailPath.getStyleClass().add("world-save-path");
        detailPath.setWrapText(true);
        configureCombos();
        detail.getChildren().addAll(detailTitle, detailMeta, detailPath,
                ui.createControlRow(Messages.get("saves.difficulty"), difficulty),
                ui.createControlRow(Messages.get("saves.gameMode"), gameMode), commands);
        saveButton = ui.createActionButton(Messages.get("saves.save"), "primary-button", this::saveSettings);
        Button folder = ui.createActionButton(Messages.get("button.openDir"), "ghost-button",
                () -> { if (selected != null) ui.openLocalFolder(selected.directory().toFile(), Messages.get("saves.title")); });
        openInstanceButton = ui.createActionButton(Messages.get("saves.openInstance"), "secondary-button",
                this::openInstance);
        HBox actions = new HBox(10, saveButton, folder, openInstanceButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(actions);
        setDetailVisible(false);

    }

    private void configureCombos() {
        difficulty.getItems().setAll(WorldSaveSettings.Difficulty.values());
        gameMode.getItems().setAll(WorldSaveSettings.GameMode.values());
        difficulty.setConverter(new StringConverter<>() {
            @Override public String toString(WorldSaveSettings.Difficulty value) { return difficultyText(value); }
            @Override public WorldSaveSettings.Difficulty fromString(String value) { return null; }
        });
        gameMode.setConverter(new StringConverter<>() {
            @Override public String toString(WorldSaveSettings.GameMode value) { return gameModeText(value); }
            @Override public WorldSaveSettings.GameMode fromString(String value) { return null; }
        });
        difficulty.setCellFactory(list -> comboCell(WorldSavesPage::difficultyText));
        difficulty.setButtonCell(comboCell(WorldSavesPage::difficultyText));
        gameMode.setCellFactory(list -> comboCell(WorldSavesPage::gameModeText));
        gameMode.setButtonCell(comboCell(WorldSavesPage::gameModeText));
        ui.applyFieldStyle(difficulty);
        ui.applyFieldStyle(gameMode);
        commands.setText(Messages.get("saves.allowCommands"));
    }

    private <T> ListCell<T> comboCell(java.util.function.Function<T, String> display) {
        return new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : display.apply(item));
            }
        };
    }

    void refresh() {
        refreshSelection(null, null);
    }

    private void refreshSelection(String groupId, String worldName) {
        explorer.setLoading();
        long generation = scanGeneration.incrementAndGet();
        ui.controller.supplyAsync("ecl-scan-worlds", () -> service.scan(ui.gameRepository()))
                .whenComplete((scanned, error) -> Platform.runLater(() -> {
                    if (generation != scanGeneration.get()) {
                        return;
                    }
                    if (error != null) {
                        ui.setStatus("读取世界失败", ui.cleanMessage(error));
                        return;
                    }
                    applyScannedWorlds(scanned == null ? List.of() : scanned, groupId, worldName);
                }));
    }

    private void applyScannedWorlds(List<WorldSave> scanned, String groupId, String worldName) {
        explorer.setWorlds(scanned, groupId, worldName);
    }

    private void showDetails(WorldSave value) {
        selected = value;
        setDetailVisible(value != null);
        if (value == null) return;
        detailTitle.setText(value.name());
        String instanceLabel = value.sharedDirectory() ? Messages.get("saves.shared")
                : Messages.format("saves.instance", value.instanceId());
        detailMeta.setText(value.minecraftVersion() + "  ·  " + value.loaderLabel()
                + "  ·  " + instanceLabel);
        detailPath.setText(value.directory().toString());
        difficulty.setValue(value.settings().difficulty());
        gameMode.setValue(value.settings().gameMode());
        commands.setSelected(value.settings().allowCommands());
        openInstanceButton.setDisable(value.sharedDirectory());
        saveButton.setDisable(ui.isVersionRunning(value.instanceId()));
    }

    private void saveSettings() {
        if (selected == null) return;
        if (ui.isVersionRunning(selected.instanceId())) {
            ui.setStatus(Messages.get("saves.save"), "实例正在运行，请退出游戏后再修改世界。");
            return;
        }
        try {
            WorldSave updated = service.update(selected, new WorldSaveSettings(difficulty.getValue(),
                    gameMode.getValue(), commands.isSelected()));
            refreshSelection(updated.groupId(), updated.name());
            ui.setStatus(Messages.get("saves.save"), Messages.format("saves.saved", updated.name()));
        } catch (IOException error) {
            ui.setStatus(Messages.get("saves.save"), error.getMessage());
        }
    }

    private void openInstance() {
        if (selected == null || selected.sharedDirectory() || selected.instanceId().isBlank()) return;
        ui.versionActions.restoreVersionComboItems(selected.instanceId());
        ui.setActiveView(AppView.HOME);
    }

    private void setDetailVisible(boolean visible) {
        detail.setVisible(visible);
        detail.setManaged(visible);
        if (!visible) {
            detailTitle.setText(Messages.get("saves.empty.title"));
            detailMeta.setText(Messages.get("saves.empty.subtitle"));
            detailPath.setText("");
            openInstanceButton.setDisable(true);
        }
    }

    private static String difficultyText(WorldSaveSettings.Difficulty value) {
        if (value == null) return "";
        return switch (value) {
            case PEACEFUL -> Messages.get("saves.difficulty.peaceful");
            case EASY -> Messages.get("saves.difficulty.easy");
            case NORMAL -> Messages.get("saves.difficulty.normal");
            case HARD -> Messages.get("saves.difficulty.hard");
        };
    }

    private static String gameModeText(WorldSaveSettings.GameMode value) {
        if (value == null) return "";
        return switch (value) {
            case SURVIVAL -> Messages.get("saves.mode.survival");
            case CREATIVE -> Messages.get("saves.mode.creative");
            case ADVENTURE -> Messages.get("saves.mode.adventure");
            case SPECTATOR -> Messages.get("saves.mode.spectator");
        };
    }

}
