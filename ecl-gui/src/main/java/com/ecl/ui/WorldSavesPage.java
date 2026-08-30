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
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Save browser grouped by Minecraft version and mod loader. */
final class WorldSavesPage extends VBox {
    private final LauncherUI ui;
    private final WorldSaveService service;
    private final ListView<SaveGroup> groups = new ListView<>();
    private final ListView<WorldSave> worlds = new ListView<>();
    private final VBox detail = new VBox(12);
    private final Label countLabel = new Label();
    private final Label detailTitle = new Label();
    private final Label detailMeta = new Label();
    private final Label detailPath = new Label();
    private final ComboBox<WorldSaveSettings.Difficulty> difficulty = new ComboBox<>();
    private final ComboBox<WorldSaveSettings.GameMode> gameMode = new ComboBox<>();
    private final CheckBox commands = new CheckBox();
    private Button openInstanceButton;
    private Button saveButton;
    private WorldSave selected;
    private List<WorldSave> allWorlds = List.of();
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
        Label subtitle = new Label(Messages.get("saves.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        Button refresh = ui.createActionButton(Messages.get("button.refresh"), "secondary-button", this::refresh);
        HBox heading = new HBox(14, new VBox(5, title, subtitle), refresh);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heading.getChildren().get(0), Priority.ALWAYS);

        groups.setPrefWidth(290);
        groups.setMinWidth(260);
        groups.getStyleClass().add("world-save-groups");
        groups.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SaveGroup item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                Label name = new Label(item.label());
                name.getStyleClass().add("world-save-group-name");
                Label count = new Label(Integer.toString(item.worlds().size()));
                count.getStyleClass().add("world-save-group-count");
                HBox row = new HBox(8, name, count);
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(name, Priority.ALWAYS);
                setGraphic(row);
            }
        });
        groups.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> showGroup(value));

        worlds.setPrefWidth(390);
        worlds.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(WorldSave item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                Label name = new Label(item.name());
                name.getStyleClass().add("world-save-name");
                Label meta = new Label(item.loaderLabel());
                meta.getStyleClass().add("world-save-meta");
                setGraphic(new VBox(3, name, meta));
            }
        });
        worlds.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> showDetails(value));

        detail.getStyleClass().addAll("surface", "world-save-detail");
        detail.setMinWidth(380);
        detail.setPrefWidth(460);
        detailTitle.getStyleClass().add("section-title");
        detailMeta.getStyleClass().add("section-subtitle");
        detailPath.getStyleClass().add("world-save-path");
        detailPath.setWrapText(true);
        detail.getChildren().addAll(detailTitle, detailMeta, detailPath);

        configureCombos();
        detail.getChildren().add(ui.createControlRow(Messages.get("saves.difficulty"), difficulty));
        detail.getChildren().add(ui.createControlRow(Messages.get("saves.gameMode"), gameMode));
        detail.getChildren().add(commands);
        saveButton = ui.createActionButton(Messages.get("saves.save"), "primary-button", this::saveSettings);
        Button folder = ui.createActionButton(Messages.get("button.openDir"), "ghost-button",
                () -> { if (selected != null) ui.openLocalFolder(selected.directory().toFile(), Messages.get("saves.title")); });
        openInstanceButton = ui.createActionButton(Messages.get("saves.openInstance"), "secondary-button",
                this::openInstance);
        HBox actions = new HBox(10, saveButton, folder, openInstanceButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(actions);
        setDetailVisible(false);

        BorderPane content = new BorderPane();
        content.setLeft(groups);
        content.setCenter(worlds);
        content.setRight(detail);
        BorderPane.setMargin(groups, new Insets(0, 12, 0, 0));
        BorderPane.setMargin(worlds, new Insets(0, 12, 0, 0));
        VBox.setVgrow(content, Priority.ALWAYS);
        getChildren().addAll(heading, countLabel, content);
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
        allWorlds = scanned;
        List<SaveGroup> values = new ArrayList<>();
        values.add(new SaveGroup(Messages.get("saves.all"), allWorlds));
        allWorlds.stream().collect(java.util.stream.Collectors.groupingBy(WorldSave::groupId))
                .values().stream().sorted(Comparator.comparing((List<WorldSave> value) -> value.get(0).minecraftVersion())
                        .thenComparing(value -> value.get(0).loaderLabel()))
                .forEach(value -> values.add(new SaveGroup(groupLabel(value.get(0)), value)));
        groups.getItems().setAll(values);
        countLabel.setText(Messages.format("saves.count", allWorlds.size()));
        if (!values.isEmpty()) {
            int groupIndex = 0;
            if (groupId != null) {
                for (int i = 1; i < values.size(); i++) {
                    if (values.get(i).worlds().stream()
                            .anyMatch(world -> groupId.equals(world.groupId()))) {
                        groupIndex = i;
                        break;
                    }
                }
            }
            groups.getSelectionModel().select(groupIndex);
            if (worldName != null) {
                for (int i = 0; i < worlds.getItems().size(); i++) {
                    if (worldName.equals(worlds.getItems().get(i).name())) {
                        worlds.getSelectionModel().select(i);
                        break;
                    }
                }
            }
        }
        else { worlds.getItems().clear(); showDetails(null); }
    }

    private void showGroup(SaveGroup group) {
        if (group == null) { worlds.getItems().clear(); showDetails(null); return; }
        worlds.getItems().setAll(group.worlds());
        if (!group.worlds().isEmpty()) worlds.getSelectionModel().select(0);
        else showDetails(null);
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

    private static String groupLabel(WorldSave save) {
        if (save.sharedDirectory()) return Messages.get("saves.shared");
        return save.minecraftVersion() + "  ·  " + save.loaderLabel();
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

    private record SaveGroup(String label, List<WorldSave> worlds) { }
}
