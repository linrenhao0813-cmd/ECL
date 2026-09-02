package com.ecl.ui;

import com.ecl.game.WorldSave;
import com.ecl.game.WorldSaveService;
import com.ecl.game.WorldSaveSettings;
import com.ecl.game.companion.CompanionBridgeDetector;
import com.ecl.game.companion.CompanionBridgeState;
import com.ecl.game.companion.CompanionTask;
import com.ecl.game.companion.CompanionTaskResult;
import com.ecl.game.companion.CompanionTaskStatus;
import com.ecl.game.companion.CompanionTaskStore;
import com.ecl.game.companion.PlayWithAiConfigService;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;
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
    private final CompanionBridgeDetector bridgeDetector = new CompanionBridgeDetector();
    private final PlayWithAiConfigService configService;
    private final ComboBox<PlayWithAiConfigService.AiProvider> aiProvider = new ComboBox<>();
    private final PasswordField apiKeyField = new PasswordField();
    private final TextField apiKeyPreview = new TextField();
    private final CheckBox showApiKey = new CheckBox();
    private final TextField baseUrlField = new TextField();
    private final TextField modelField = new TextField();
    private final Label apiKeyStatus = new Label();
    private final Label apiConfigStatus = new Label();
    private final Label apiConfigPath = new Label();
    private Button saveApiButton;
    private Button clearApiKeyButton;
    private PlayWithAiConfigService.Config apiConfig;
    private boolean apiConfigLoading;
    private boolean apiConfigSaving;
    private String autoBaseUrl = "";
    private String autoModel = "";
    private final AtomicLong apiConfigGeneration = new AtomicLong();
    private final ComboBox<TaskTemplate> taskTemplate = new ComboBox<>();
    private final TextField taskQuantity = new TextField("3");
    private final TextArea customInstruction = new TextArea();
    private final Label assistantCompatibility = new Label();
    private final Label assistantBinding = new Label();
    private final ListView<AssistantTask> assistantTasks = new ListView<>();
    private final CheckBox sharedInstanceConfirmation = new CheckBox();
    private Button assistantEnqueueButton;
    private Button assistantCancelButton;
    private CompanionBridgeState assistantState;
    private CompanionTaskStore assistantStore;
    private final AtomicLong assistantGeneration = new AtomicLong();

    WorldSavesPage(LauncherUI ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.service = new WorldSaveService(ui::isVersionRunning);
        this.configService = new PlayWithAiConfigService(ui::isVersionRunning);
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
        configureCombos();
        VBox settingsPane = new VBox(12, detailTitle, detailMeta, detailPath,
                ui.createControlRow(Messages.get("saves.difficulty"), difficulty),
                ui.createControlRow(Messages.get("saves.gameMode"), gameMode), commands);
        saveButton = ui.createActionButton(Messages.get("saves.save"), "primary-button", this::saveSettings);
        Button folder = ui.createActionButton(Messages.get("button.openDir"), "ghost-button",
                () -> { if (selected != null) ui.openLocalFolder(selected.directory().toFile(), Messages.get("saves.title")); });
        openInstanceButton = ui.createActionButton(Messages.get("saves.openInstance"), "secondary-button",
                this::openInstance);
        HBox actions = new HBox(10, saveButton, folder, openInstanceButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        settingsPane.getChildren().add(actions);
        Tab settingsTab = new Tab(Messages.get("saves.settingsTab"), settingsPane);
        settingsTab.setClosable(false);
        ScrollPane assistantScroll = new ScrollPane(buildAssistantPane());
        assistantScroll.setFitToWidth(true);
        assistantScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        assistantScroll.getStyleClass().add("assistant-scroll");
        Tab assistantTab = new Tab(Messages.get("saves.assistantTab"), assistantScroll);
        assistantTab.setClosable(false);
        TabPane tabs = new TabPane(settingsTab, assistantTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("world-save-tabs");
        detail.getChildren().add(tabs);
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

    private VBox buildAssistantPane() {
        configureAiConfigControls();
        assistantCompatibility.getStyleClass().add("assistant-status");
        assistantCompatibility.setWrapText(true);
        assistantBinding.getStyleClass().add("assistant-binding");
        assistantBinding.setWrapText(true);

        taskTemplate.getItems().setAll(TaskTemplate.values());
        taskTemplate.setValue(TaskTemplate.MINE);
        taskTemplate.setConverter(new StringConverter<>() {
            @Override public String toString(TaskTemplate value) { return templateText(value); }
            @Override public TaskTemplate fromString(String value) { return null; }
        });
        taskTemplate.setCellFactory(list -> comboCell(WorldSavesPage::templateText));
        taskTemplate.setButtonCell(comboCell(WorldSavesPage::templateText));
        ui.applyFieldStyle(taskTemplate);
        taskQuantity.setPromptText(Messages.get("saves.assistant.quantity"));
        ui.applyFieldStyle(taskQuantity);
        taskQuantity.setPrefWidth(100);
        customInstruction.setPromptText(Messages.get("saves.assistant.custom.prompt"));
        customInstruction.setPrefRowCount(2);
        customInstruction.setWrapText(true);
        ui.applyFieldStyle(customInstruction);
        sharedInstanceConfirmation.setText(Messages.get("saves.assistant.shared.confirm"));
        sharedInstanceConfirmation.setWrapText(true);
        sharedInstanceConfirmation.selectedProperty().addListener((obs, old, value) -> updateAssistantControls());

        assistantEnqueueButton = ui.createActionButton(Messages.get("saves.assistant.enqueue"),
                "primary-button", this::enqueueAssistantTask);
        assistantCancelButton = ui.createActionButton(Messages.get("saves.assistant.cancelWaiting"),
                "ghost-button", this::cancelWaitingTasks);
        Button refresh = ui.createActionButton(Messages.get("button.refresh"), "secondary-button",
                this::refreshAssistant);
        HBox actions = new HBox(8, assistantEnqueueButton, assistantCancelButton, refresh);
        actions.setAlignment(Pos.CENTER_LEFT);

        assistantTasks.setPrefHeight(230);
        assistantTasks.setPlaceholder(new Label(Messages.get("saves.assistant.history.empty")));
        assistantTasks.setCellFactory(list -> new AssistantTaskCell());

        Label apiTitle = new Label(Messages.get("saves.assistant.api.title"));
        apiTitle.getStyleClass().add("section-title");
        Label apiNotice = new Label(Messages.get("saves.assistant.api.notice"));
        apiNotice.getStyleClass().add("content-subtitle");
        apiNotice.setWrapText(true);
        apiConfigStatus.getStyleClass().add("assistant-status");
        apiConfigStatus.setWrapText(true);
        apiConfigPath.getStyleClass().add("content-subtitle");
        apiConfigPath.setWrapText(true);
        apiKeyStatus.getStyleClass().add("content-subtitle");
        HBox apiKeyActions = new HBox(8, showApiKey, apiKeyStatus);
        apiKeyActions.setAlignment(Pos.CENTER_LEFT);
        saveApiButton = ui.createActionButton(Messages.get("saves.assistant.api.save"),
                "primary-button", this::saveApiSettings);
        clearApiKeyButton = ui.createActionButton(Messages.get("saves.assistant.api.clear"),
                "ghost-button", this::clearApiKey);
        HBox apiActions = new HBox(8, saveApiButton, clearApiKeyButton);
        apiActions.setAlignment(Pos.CENTER_LEFT);
        VBox apiPane = new VBox(8,
                apiTitle,
                apiNotice,
                apiConfigStatus,
                apiConfigPath,
                ui.createControlRow(Messages.get("saves.assistant.api.provider"), aiProvider),
                ui.createControlRow(Messages.get("saves.assistant.api.key"), apiKeyField),
                apiKeyPreview,
                apiKeyActions,
                ui.createControlRow(Messages.get("saves.assistant.api.baseUrl"), baseUrlField),
                ui.createControlRow(Messages.get("saves.assistant.api.model"), modelField),
                apiActions);
        apiPane.getStyleClass().add("assistant-api-pane");

        VBox pane = new VBox(10,
                apiPane,
                assistantCompatibility,
                assistantBinding,
                ui.createControlRow(Messages.get("saves.assistant.template"), taskTemplate),
                ui.createControlRow(Messages.get("saves.assistant.quantity"), taskQuantity),
                customInstruction,
                sharedInstanceConfirmation,
                new Label(Messages.get("saves.assistant.offlineHint")),
                actions,
                assistantTasks);
        pane.getStyleClass().add("assistant-pane");
        VBox.setVgrow(assistantTasks, Priority.ALWAYS);
        updateAssistantControls();
        return pane;
    }

    private void configureAiConfigControls() {
        aiProvider.getItems().setAll(PlayWithAiConfigService.AiProvider.values());
        aiProvider.setCellFactory(list -> comboCell(value -> value == null ? "" : value.label));
        aiProvider.setButtonCell(comboCell(value -> value == null ? "" : value.label));
        aiProvider.setOnAction(event -> applyProviderDefaults());
        apiKeyField.setPromptText(Messages.get("saves.assistant.api.key.prompt"));
        apiKeyPreview.setEditable(false);
        apiKeyPreview.setFocusTraversable(false);
        apiKeyPreview.setVisible(false);
        apiKeyPreview.setManaged(false);
        apiKeyPreview.getStyleClass().add("field-control");
        showApiKey.setText(Messages.get("saves.assistant.api.key.show"));
        showApiKey.selectedProperty().addListener((obs, old, value) -> updateApiKeyVisibility());
        apiKeyField.textProperty().addListener((obs, old, value) -> updateApiKeyStatus());
        for (TextField field : List.of(apiKeyField, baseUrlField, modelField)) {
            ui.applyFieldStyle(field);
        }
        ui.applyFieldStyle(aiProvider);
        ui.applyFieldStyle(apiKeyPreview);
        baseUrlField.setPromptText(Messages.get("saves.assistant.api.baseUrl.prompt"));
        modelField.setPromptText(Messages.get("saves.assistant.api.model.prompt"));
        aiProvider.setValue(PlayWithAiConfigService.AiProvider.OPENAI);
        baseUrlField.setText(PlayWithAiConfigService.AiProvider.OPENAI.defaultBaseUrl);
        modelField.setText(PlayWithAiConfigService.AiProvider.OPENAI.defaultModel);
        autoBaseUrl = baseUrlField.getText();
        autoModel = modelField.getText();
        updateApiKeyStatus();
    }

    private void applyProviderDefaults() {
        if (apiConfigLoading || aiProvider.getValue() == null) return;
        PlayWithAiConfigService.AiProvider provider = aiProvider.getValue();
        if (baseUrlField.getText().isBlank() || baseUrlField.getText().equals(autoBaseUrl)) {
            baseUrlField.setText(provider.defaultBaseUrl);
        }
        if (modelField.getText().isBlank() || modelField.getText().equals(autoModel)) {
            modelField.setText(provider.defaultModel);
        }
        autoBaseUrl = baseUrlField.getText();
        autoModel = modelField.getText();
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
        sharedInstanceConfirmation.setSelected(!value.sharedDirectory());
        refreshAssistant();
        refreshApiConfig();
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

    private void refreshAssistant() {
        WorldSave value = selected;
        if (value == null || ui.controller == null) {
            assistantTasks.getItems().clear();
            assistantState = null;
            assistantStore = null;
            updateAssistantControls();
            return;
        }
        long generation = assistantGeneration.incrementAndGet();
        ui.controller.supplyAsync("ecl-companion-status", () -> readAssistantSnapshot(value))
                .whenComplete((snapshot, error) -> Platform.runLater(() -> {
                    if (generation != assistantGeneration.get() || selected != value) return;
                    if (error != null) {
                        assistantState = new CompanionBridgeState(CompanionBridgeState.Status.INCOMPATIBLE,
                                "", 0, null, "", ui.cleanMessage(error), value.sharedDirectory());
                        assistantTasks.getItems().clear();
                    } else {
                        assistantState = snapshot.state();
                        assistantStore = snapshot.store();
                        assistantTasks.getItems().setAll(snapshot.tasks());
                    }
                    updateAssistantControls();
                }));
    }

    private void refreshApiConfig() {
        WorldSave value = selected;
        long generation = apiConfigGeneration.incrementAndGet();
        if (value == null || value.instanceId().isBlank() || ui.controller == null) {
            apiConfig = null;
            setApiConfigFields(null);
            updateAssistantControls();
            return;
        }
        apiConfigLoading = true;
        updateAssistantControls();
        ui.controller.supplyAsync("ecl-companion-config", () -> {
                    try {
                        return configService.load(ui.gameRepository(), value.instanceId());
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .whenComplete((config, error) -> Platform.runLater(() -> {
                    if (generation != apiConfigGeneration.get() || selected != value) return;
                    apiConfigLoading = false;
                    if (error != null) {
                        apiConfig = null;
                        setApiConfigFields(null);
                        apiConfigStatus.setText(Messages.get("saves.assistant.api.unavailable"));
                        apiConfigPath.setText("");
                    } else {
                        apiConfig = config;
                        setApiConfigFields(config);
                        apiConfigStatus.setText(config.instanceRunning()
                                ? Messages.get("saves.assistant.api.running")
                                : Messages.format("saves.assistant.api.status",
                                maskApiKey(config.apiKey())));
                        apiConfigPath.setText(Messages.format("saves.assistant.api.path",
                                config.path()));
                    }
                    updateAssistantControls();
                }));
    }

    private void setApiConfigFields(PlayWithAiConfigService.Config config) {
        apiConfigLoading = true;
        try {
            showApiKey.setSelected(false);
            if (config == null) {
                aiProvider.setValue(PlayWithAiConfigService.AiProvider.OPENAI);
                apiKeyField.clear();
                baseUrlField.setText(PlayWithAiConfigService.AiProvider.OPENAI.defaultBaseUrl);
                modelField.setText(PlayWithAiConfigService.AiProvider.OPENAI.defaultModel);
                autoBaseUrl = baseUrlField.getText();
                autoModel = modelField.getText();
                apiConfigStatus.setText(Messages.get("saves.assistant.api.loading"));
                apiConfigPath.setText("");
            } else {
                aiProvider.setValue(config.aiProvider());
                apiKeyField.setText(config.apiKey());
                baseUrlField.setText(config.baseUrl());
                modelField.setText(config.model());
                autoBaseUrl = config.baseUrl();
                autoModel = config.model();
                updateApiConfigStatus(maskApiKey(config.apiKey()));
            }
        } finally {
            apiConfigLoading = false;
            updateApiKeyVisibility();
            updateApiKeyStatus();
        }
    }

    private void saveApiSettings() {
        persistApiSettings(apiKeyField.getText());
    }

    private void clearApiKey() {
        persistApiSettings("");
    }

    private void persistApiSettings(String apiKey) {
        WorldSave value = selected;
        if (value == null || value.instanceId().isBlank() || apiConfig == null
                || ui.isVersionRunning(value.instanceId()) || apiConfigSaving) {
            return;
        }
        PlayWithAiConfigService.AiProvider provider = aiProvider.getValue();
        if (provider == null) return;
        String baseUrl = baseUrlField.getText();
        String model = modelField.getText();
        apiConfigSaving = true;
        updateAssistantControls();
        ui.controller.supplyAsync("ecl-companion-config-save", () -> {
                    try {
                        configService.save(ui.gameRepository(), value.instanceId(), provider,
                                apiKey, baseUrl, model);
                        return null;
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    apiConfigSaving = false;
                    if (error != null) {
                        ui.setStatus(Messages.get("saves.assistant.api.save"),
                                ui.cleanMessage(error));
                    } else {
                        ui.setStatus(Messages.get("saves.assistant.api.save"),
                                Messages.get(apiKey.isBlank()
                                        ? "saves.assistant.api.cleared"
                                        : "saves.assistant.api.saved"));
                        refreshApiConfig();
                    }
                    updateAssistantControls();
                }));
    }

    private void updateApiConfigStatus(String maskedKey) {
        apiConfigStatus.setText(Messages.format("saves.assistant.api.status", maskedKey));
    }

    private void updateApiKeyVisibility() {
        boolean reveal = showApiKey.isSelected();
        apiKeyPreview.setText(maskApiKey(apiKeyField.getText()));
        apiKeyField.setVisible(!reveal);
        apiKeyField.setManaged(!reveal);
        apiKeyPreview.setVisible(reveal);
        apiKeyPreview.setManaged(reveal);
    }

    private void updateApiKeyStatus() {
        String masked = maskApiKey(apiKeyField.getText());
        apiKeyStatus.setText(Messages.format("saves.assistant.api.key.status", masked));
        if (showApiKey.isSelected()) apiKeyPreview.setText(masked);
    }

    private static String maskApiKey(String value) {
        if (value == null || value.isBlank()) return Messages.get("saves.assistant.api.notConfigured");
        String trimmed = value.trim();
        if (trimmed.length() <= 4) return Messages.get("saves.assistant.api.configured");
        return Messages.format("saves.assistant.api.configuredSuffix",
                trimmed.substring(trimmed.length() - 4));
    }

    private AssistantSnapshot readAssistantSnapshot(WorldSave value) {
        CompanionTaskStore store = new CompanionTaskStore(value.directory());
        CompanionBridgeState state = bridgeDetector.detect(value, ui.gameRepository());
        try {
            List<AssistantTask> history = new ArrayList<>();
            for (CompanionTask task : store.listTasks()) {
                history.add(new AssistantTask(task, store.readStatus(task)));
            }
            return new AssistantSnapshot(store, state, history);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取 Companion 任务历史", error);
        }
    }

    private void enqueueAssistantTask() {
        if (selected == null || assistantStore == null || assistantState == null
                || !assistantState.canSubmit() || (selected.sharedDirectory()
                && !sharedInstanceConfirmation.isSelected())) {
            return;
        }
        String instruction;
        try {
            instruction = customInstruction.getText() == null || customInstruction.getText().isBlank()
                    ? templateInstruction() : customInstruction.getText().trim();
            UUID targetPlayer = assistantState.boundPlayerUuid();
            CompanionTaskStore store = assistantStore;
            ui.controller.supplyAsync("ecl-companion-submit", () -> {
                        try {
                            return store.enqueue(instruction, targetPlayer, true);
                        } catch (IOException error) {
                            throw new IllegalStateException(error);
                        }
                    })
                    .whenComplete((task, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            ui.setStatus(Messages.get("saves.assistant.enqueue"), ui.cleanMessage(error));
                        } else {
                            ui.setStatus(Messages.get("saves.assistant.enqueue"),
                                    Messages.format("saves.assistant.enqueued", task.instruction()));
                            customInstruction.clear();
                            refreshAssistant();
                        }
                    }));
        } catch (RuntimeException error) {
            ui.setStatus(Messages.get("saves.assistant.enqueue"), error.getMessage());
        }
    }

    private String templateInstruction() {
        TaskTemplate template = taskTemplate.getValue() == null ? TaskTemplate.MINE : taskTemplate.getValue();
        if (template == TaskTemplate.SHOVEL) return "做个木锹";
        int amount = ui.parseRangedInt(taskQuantity.getText(), Messages.get("saves.assistant.quantity"), 1, 64);
        return switch (template) {
            case MINE -> "挖" + amount + "格";
            case CHOP -> "砍" + amount + "棵树";
            case SHOVEL -> "做个木锹";
            case IRON -> "做" + amount + "个铁锭";
        };
    }

    private void cancelWaitingTasks() {
        CompanionTaskStore store = assistantStore;
        if (store == null) return;
        ui.controller.supplyAsync("ecl-companion-cancel", () -> {
            int cancelled = 0;
            try {
                for (CompanionTask task : store.listTasks()) {
                    CompanionTaskResult result = store.readStatus(task);
                    if (isWaiting(result.status())) {
                        store.cancel(task.taskId());
                        cancelled++;
                    }
                }
                return cancelled;
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }).whenComplete((count, error) -> Platform.runLater(() -> {
            if (error != null) ui.setStatus(Messages.get("saves.assistant.cancelWaiting"), ui.cleanMessage(error));
            else {
                ui.setStatus(Messages.get("saves.assistant.cancelWaiting"),
                        Messages.format("saves.assistant.cancelled", count));
                refreshAssistant();
            }
        }));
    }

    private void cancelTask(AssistantTask item) {
        if (!isWaiting(item.result().status()) || assistantStore == null) return;
        CompanionTaskStore store = assistantStore;
        ui.controller.supplyAsync("ecl-companion-cancel", () -> {
            try {
                store.cancel(item.task().taskId());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            return null;
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) ui.setStatus(Messages.get("saves.assistant.cancelWaiting"), ui.cleanMessage(error));
            else refreshAssistant();
        }));
    }

    private void updateAssistantControls() {
        boolean selectedShared = selected != null && selected.sharedDirectory();
        sharedInstanceConfirmation.setVisible(selectedShared);
        sharedInstanceConfirmation.setManaged(selectedShared);
        boolean allowed = assistantState != null && assistantState.canSubmit()
                && (!selectedShared || sharedInstanceConfirmation.isSelected());
        if (assistantEnqueueButton != null) assistantEnqueueButton.setDisable(!allowed);
        if (assistantCancelButton != null) {
            assistantCancelButton.setDisable(assistantTasks.getItems().stream()
                    .noneMatch(item -> isWaiting(item.result().status())));
        }
        boolean configEditable = selected != null && !selected.instanceId().isBlank()
                && apiConfig != null && !apiConfigLoading && !apiConfigSaving
                && !apiConfig.instanceRunning()
                && !ui.isVersionRunning(selected.instanceId());
        aiProvider.setDisable(!configEditable);
        apiKeyField.setDisable(!configEditable);
        apiKeyPreview.setDisable(!configEditable);
        baseUrlField.setDisable(!configEditable);
        modelField.setDisable(!configEditable);
        showApiKey.setDisable(apiConfig == null);
        if (saveApiButton != null) saveApiButton.setDisable(!configEditable);
        if (clearApiKeyButton != null) clearApiKeyButton.setDisable(!configEditable);
        if (assistantState == null) {
            assistantCompatibility.setText(Messages.get("saves.assistant.compatibility.unknown"));
            assistantBinding.setText("");
        } else {
            assistantCompatibility.setText(Messages.format("saves.assistant.compatibility",
                    assistantStatusText(assistantState.status()), assistantState.message()));
            assistantBinding.setText(assistantState.boundPlayerUuid() == null
                    ? Messages.get("saves.assistant.unbound")
                    : Messages.format("saves.assistant.bound", assistantState.boundPlayerName(),
                    assistantState.boundPlayerUuid()));
        }
    }

    private static boolean isWaiting(CompanionTaskStatus status) {
        return status == CompanionTaskStatus.QUEUED || status == CompanionTaskStatus.WAITING_FOR_PLAYER
                || status == CompanionTaskStatus.WAITING_FOR_COMPANION || status == CompanionTaskStatus.PAUSED;
    }

    private static String assistantStatusText(CompanionBridgeState.Status status) {
        return switch (status) {
            case INSTALLED -> Messages.get("saves.assistant.compatibility.installed");
            case NOT_INSTALLED -> Messages.get("saves.assistant.compatibility.notInstalled");
            case INCOMPATIBLE -> Messages.get("saves.assistant.compatibility.incompatible");
            case UNBOUND -> Messages.get("saves.assistant.compatibility.unbound");
        };
    }

    private static String taskStatusText(CompanionTaskStatus status) {
        return Messages.get("saves.assistant.status." + status.name().toLowerCase(Locale.ROOT));
    }

    private static String templateText(TaskTemplate value) {
        if (value == null) return "";
        return Messages.get("saves.assistant.template." + value.name().toLowerCase(Locale.ROOT));
    }

    private final class AssistantTaskCell extends ListCell<AssistantTask> {
        @Override protected void updateItem(AssistantTask item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            CompanionTask task = item.task();
            CompanionTaskResult result = item.result();
            Label title = new Label(task.instruction());
            title.getStyleClass().add("content-title");
            Label status = new Label(taskStatusText(result.status()));
            status.getStyleClass().add("status-detail");
            Label meta = new Label(Messages.format("saves.assistant.task.meta",
                    result.completedActions(), result.requestedActions(), formatTime(task.createdAt()),
                    formatTime(result.finishedAt())));
            meta.getStyleClass().add("content-subtitle");
            Label message = new Label(result.message());
            message.getStyleClass().add("content-subtitle");
            message.setWrapText(true);
            VBox text = new VBox(3, title, status, meta, message);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button cancel = isWaiting(result.status())
                    ? ui.createActionButton(Messages.get("saves.assistant.cancel"), "ghost-button",
                    () -> cancelTask(item)) : null;
            HBox row = new HBox(10, text);
            if (cancel != null) row.getChildren().add(cancel);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 4, 6, 4));
            setGraphic(row);
        }
    }

    private static String formatTime(String value) {
        if (value == null || value.isBlank()) return "—";
        try {
            return Instant.parse(value).toString().replace('T', ' ').replace("Z", "");
        } catch (RuntimeException ignored) {
            return value;
        }
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

    private enum TaskTemplate { MINE, CHOP, SHOVEL, IRON }

    private record AssistantTask(CompanionTask task, CompanionTaskResult result) { }

    private record AssistantSnapshot(CompanionTaskStore store, CompanionBridgeState state,
                                     List<AssistantTask> tasks) { }

    private record SaveGroup(String label, List<WorldSave> worlds) { }
}
