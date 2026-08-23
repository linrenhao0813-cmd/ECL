package com.ecl.modrinth.ui;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ContentSource;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.service.SequentialBatchRunner;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import com.ecl.ui.MainController;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ModBrowserView extends VBox implements AutoCloseable {
    private final MainController controller;
    private final ModBrowserViewModel viewModel;
    private final ModInstallWorkflow installWorkflow;
    private final Consumer<String> statusConsumer;
    private final Label instanceLabel = new Label();
    private final Label emptyLabel = new Label("输入关键词或直接浏览兼容模组");
    private final ListView<ModProject> resultList = new ListView<>();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(400));
    private final ModExternalActions externalActions;
    private final ModDetailsPane detailsPane;
    private Tab installedTab;
    private Tab updatesTab;
    private InstalledModsPane installedPane;
    private ModUpdatesPane updatesPane;
    private boolean updateBatchRunning;
    private ModProject selectedProject;

    public ModBrowserView(
            MainController controller,
            ModInstanceContext initialInstance,
            Consumer<String> statusConsumer
    ) {
        this.controller = controller;
        this.statusConsumer = statusConsumer == null ? ignored -> { } : statusConsumer;
        this.externalActions = new ModExternalActions(this.statusConsumer);
        this.viewModel = new ModBrowserViewModel(
                controller.metadataProvider(ContentSource.MODRINTH),
                controller.modDependencyResolver(),
                controller.installationPlanBuilder(),
                controller.modInstallationService(),
                controller.modManagementService(),
                controller.localModScanner(),
                controller.modUpdateService(),
                controller.downloadTasks());
        this.installWorkflow = new ModInstallWorkflow(
                viewModel, this::preferredReleaseChannel);
        this.detailsPane = new ModDetailsPane(controller, viewModel, externalActions,
                this::preferredReleaseChannel, this::showProject,
                version -> installWorkflow.prepare(version, "无法准备安装计划"));
        controller.registerModInstance(initialInstance);
        viewModel.setInstance(initialInstance);
        buildView(initialInstance);
        bindState();
        viewModel.search(false);
    }

    private void buildView(ModInstanceContext context) {
        getStyleClass().addAll("mod-browser", "surface");
        setSpacing(12);
        setPadding(new Insets(16));
        setFillWidth(true);

        Label eyebrow = new Label("MODRINTH + CURSEFORGE / MODS");
        eyebrow.getStyleClass().add("eyebrow");
        instanceLabel.setText(ModUiFormatter.instanceText(context));
        instanceLabel.getStyleClass().add("mod-instance-badge");
        Label scope = new Label("搜索与安装始终锁定当前实例的 Minecraft 版本和加载器");
        scope.getStyleClass().add("status-detail");
        scope.setWrapText(true);
        VBox heading = new VBox(4, eyebrow, instanceLabel, scope);

        Tab browserTab = new Tab("浏览模组", createBrowserPane());
        installedTab = new Tab("已安装", createInstalledPane());
        updatesTab = new Tab("更新", createUpdatesPane());
        browserTab.setClosable(false);
        installedTab.setClosable(false);
        updatesTab.setClosable(false);
        TabPane tabs = new TabPane(browserTab, installedTab, updatesTab);
        tabs.getStyleClass().add("mod-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab == installedTab || newTab == updatesTab) {
                viewModel.ensureUpdatesChecked(preferredReleaseChannel());
            }
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);

        ProgressBar progress = new ProgressBar();
        progress.progressProperty().bind(viewModel.overallProgressProperty());
        progress.visibleProperty().bind(viewModel.loadingProperty());
        progress.managedProperty().bind(progress.visibleProperty());
        progress.setMaxWidth(Double.MAX_VALUE);

        Label operation = new Label();
        operation.textProperty().bind(viewModel.currentOperationProperty());
        operation.getStyleClass().add("status-detail");
        Label error = new Label();
        error.textProperty().bind(viewModel.errorMessageProperty());
        error.visibleProperty().bind(viewModel.errorMessageProperty().isNotEmpty());
        error.managedProperty().bind(error.visibleProperty());
        error.getStyleClass().add("mod-error");
        error.setWrapText(true);
        Button cancel = ModUiControls.button("取消", "ghost-button");
        cancel.visibleProperty().bind(viewModel.operationCancellableProperty());
        cancel.managedProperty().bind(cancel.visibleProperty());
        cancel.setOnAction(event -> viewModel.cancelActiveRequest());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox operationRow = new HBox(8, operation, spacer, cancel);
        operationRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(heading, tabs, progress, operationRow, error);
    }

    private Node createBrowserPane() {
        TextField search = new TextField();
        search.setPromptText("搜索模组，例如 sodium、journeymap");
        search.textProperty().bindBidirectional(viewModel.searchTextProperty());
        HBox.setHgrow(search, Priority.ALWAYS);

        ComboBox<ModSearchIndex> sort = new ComboBox<>();
        sort.getItems().setAll(ModSearchIndex.values());
        sort.valueProperty().bindBidirectional(viewModel.sortIndexProperty());
        sort.setButtonCell(new ModSearchIndexCell());
        sort.setCellFactory(list -> new ModSearchIndexCell());
        sort.setPrefWidth(122);

        ComboBox<ModCategoryChoice> category = new ComboBox<>();
        category.getItems().setAll(
                new ModCategoryChoice("全部分类", ""),
                new ModCategoryChoice("性能优化", "optimization"),
                new ModCategoryChoice("科技", "technology"),
                new ModCategoryChoice("冒险", "adventure"),
                new ModCategoryChoice("魔法", "magic"),
                new ModCategoryChoice("实用工具", "utility"),
                new ModCategoryChoice("装饰", "decoration"));
        category.getSelectionModel().selectFirst();
        category.setPrefWidth(126);
        category.setOnAction(event -> {
            ModCategoryChoice selected = category.getValue();
            viewModel.setCategory(selected == null ? "" : selected.id());
            viewModel.search(false);
        });

        ComboBox<ModMetadataProvider> source = new ComboBox<>();
        source.getItems().setAll(controller.metadataProviders());
        source.setCellFactory(list -> new ModProviderCell());
        source.setButtonCell(new ModProviderCell());
        source.getSelectionModel().select(controller.metadataProvider(viewModel.contentSource()));
        source.setPrefWidth(112);
        source.setOnAction(event -> {
            ModMetadataProvider selected = source.getValue();
            if (selected != null) {
                MainController.ModSourceServices services = controller.modSourceServices(selected);
                viewModel.setMetadataProvider(selected, services.dependencyResolver(),
                        services.localScanner(), services.updateService());
                viewModel.search(false);
            }
        });

        Button searchButton = ModUiControls.button("搜索", "primary-button");
        searchButton.setMinWidth(64);
        searchButton.setOnAction(event -> viewModel.search(false));
        HBox searchBar = new HBox(8, search, searchButton);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        HBox filterBar = new HBox(8, category, sort, source);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        searchDebounce.setOnFinished(event -> viewModel.search(false));
        search.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        sort.setOnAction(event -> viewModel.search(false));

        resultList.setItems(viewModel.searchResults());
        resultList.setCellFactory(list -> new ModProjectCell(viewModel));
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPlaceholder(emptyLabel);
        resultList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showProject(newValue));
        VBox.setVgrow(resultList, Priority.ALWAYS);

        Button loadMore = ModUiControls.button("加载更多", "ghost-button");
        loadMore.setMaxWidth(Double.MAX_VALUE);
        loadMore.setOnAction(event -> viewModel.search(true));
        Button retry = ModUiControls.button("重试", "secondary-button");
        retry.visibleProperty().bind(viewModel.errorMessageProperty().isNotEmpty());
        retry.managedProperty().bind(retry.visibleProperty());
        retry.setOnAction(event -> viewModel.search(false));
        HBox resultActions = new HBox(8, loadMore, retry);
        HBox.setHgrow(loadMore, Priority.ALWAYS);

        VBox left = new VBox(8, searchBar, filterBar, resultList, resultActions);
        left.setMinWidth(350);
        SplitPane.setResizableWithParent(left, true);

        VBox detail = (VBox) detailsPane.create();
        detail.setMinWidth(300);
        SplitPane split = new SplitPane(left, detail);
        split.getStyleClass().add("mod-browser-split");
        split.setDividerPositions(0.54);
        return split;
    }

    private Node createInstalledPane() {
        installedPane = new InstalledModsPane(viewModel, this::operateSelected,
                this::uninstallSelected,
                () -> viewModel.checkUpdates(preferredReleaseChannel()),
                this::updateSelected, this::updateAll, this::chooseHistoryVersion,
                this::openSelectedProject, this::openSelectedFile, this::importLocalJar);
        return installedPane;
    }

    private Node createUpdatesPane() {
        updatesPane = new ModUpdatesPane(viewModel,
                () -> viewModel.checkUpdates(preferredReleaseChannel()), this::runUpdates);
        return updatesPane;
    }

    private void bindState() {
        viewModel.loadingProperty().addListener((observable, oldValue, loading) -> {
            resultList.setDisable(loading);
            refreshUpdateButtons();
        });
        viewModel.errorMessageProperty().addListener((observable, oldValue, message) -> {
            if (message != null && !message.isBlank()) {
                statusConsumer.accept(message);
            }
        });
        viewModel.installedMods().addListener((ListChangeListener<InstalledMod>) change -> {
            resultList.refresh();
            installedPane.refresh();
            updatesPane.refreshItems();
            refreshUpdateButtons();
        });
        viewModel.searchResults().addListener((ListChangeListener<ModProject>) change ->
                RemoteImageLoader.prefetch(viewModel.searchResults().stream()
                        .map(ModProject::iconUrl).filter(java.util.Objects::nonNull).toList()));
        viewModel.updateCountProperty().addListener((observable, oldValue, newValue) -> {
            int count = newValue == null ? 0 : newValue.intValue();
            installedTab.setText(count > 0 ? "已安装 (" + count + ")" : "已安装");
            updatesTab.setText(count > 0 ? "更新 (" + count + ")" : "更新");
            updatesPane.refreshItems();
            installedPane.refresh();
            resultList.refresh();
            refreshUpdateButtons();
        });
    }

    private void showProject(ModProject project) {
        selectedProject = project;
        detailsPane.showProject(project);
    }

    private ReleaseChannel preferredReleaseChannel() {
        return controller.preferredModReleaseChannel();
    }

    static String planFailureReason(Throwable error) {
        return ModFailureMessages.planFailureReason(error);
    }

    private void operateSelected(boolean enabled) {
        List<String> ids = selectedInstalledIds();
        if (!ids.isEmpty()) {
            viewModel.setEnabled(ids, enabled);
        }
    }

    private void uninstallSelected() {
        List<String> ids = selectedInstalledIds();
        if (ids.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将卸载 " + ids.size() + " 个模组。配置文件会保留。",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("确认卸载");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            viewModel.uninstall(ids);
        }
    }

    private void updateSelected() {
        Set<String> selectedIds = installedPane.selectedItems().stream()
                .map(InstalledMod::projectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ModUpdate> selected = viewModel.availableUpdates().stream()
                .filter(update -> selectedIds.contains(update.installedMod().projectId()))
                .toList();
        runUpdates(selected);
    }

    private void updateSelectedUpdates() {
        runUpdates(updatesPane.selectedUpdates());
    }

    private void updateAll() {
        runUpdates(List.copyOf(viewModel.availableUpdates()));
    }

    private void runUpdates(List<ModUpdate> requestedUpdates) {
        List<ModUpdate> updates = requestedUpdates == null ? List.of() : List.copyOf(requestedUpdates);
        if (updates.isEmpty()) {
            statusConsumer.accept("没有可用更新");
            return;
        }
        updateBatchRunning = true;
        refreshUpdateButtons();
        runSequentialUpdateBatch(
                updates,
                update -> viewModel.applyUpdate(update.installedMod().projectId()))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    updateBatchRunning = false;
                    viewModel.refreshInstalled();
                    refreshUpdateButtons();
                    if (error != null) {
                        statusConsumer.accept(planFailureReason(error));
                        return;
                    }
                    if (result.failures().isEmpty()) {
                        statusConsumer.accept("全部更新完成：" + result.succeeded() + " 个成功");
                        return;
                    }
                    String failedNames = result.failures().stream()
                            .map(failure -> failure.item().installedMod().displayName())
                            .distinct()
                            .reduce((left, right) -> left + "、" + right)
                            .orElse("未知模组");
                    statusConsumer.accept("批量更新完成：" + result.succeeded() + " 个成功，"
                            + result.failures().size() + " 个失败（" + failedNames + "）");
                }));
    }

    static <T> CompletableFuture<SequentialBatchRunner.Result<T>> runSequentialUpdateBatch(
            List<T> updates, Function<T, ? extends CompletableFuture<?>> operation) {
        return ModBatchUpdates.run(updates, operation);
    }

    private void refreshUpdateButtons() {
        if (updatesPane != null) {
            updatesPane.refreshButtons(updateBatchRunning);
        }
    }

    private void chooseHistoryVersion() {
        InstalledMod selected = installedPane.selectedItem();
        if (selected == null || selected.projectId().startsWith("local:")) {
            return;
        }
        viewModel.loadVersions(selected.projectId()).whenComplete((versions, error) -> {
            if (error != null) {
                return;
            }
            Platform.runLater(() -> {
                List<String> labels = versions.stream()
                        .map(version -> version.versionNumber() + " · " + version.versionType())
                        .toList();
                ChoiceDialog<String> choice = new ChoiceDialog<>(
                        labels.isEmpty() ? null : labels.getFirst(), labels);
                choice.setTitle("选择历史版本");
                choice.setHeaderText(selected.displayName());
                choice.setContentText("兼容版本:");
                choice.showAndWait().ifPresent(label -> {
                    int index = labels.indexOf(label);
                    if (index < 0) {
                        return;
                    }
                    ModVersion version = versions.get(index);
                    viewModel.preparePlan(
                            version, Set.of(), preferredReleaseChannel()).whenComplete((plan, failure) -> {
                            Platform.runLater(() -> installWorkflow.handlePreparedPlan(
                                    version, plan, failure, "无法准备安装计划"));
                        });
                });
            });
        });
    }

    private void openSelectedProject() {
        InstalledMod selected = installedPane.selectedItem();
        if (selected != null && !selected.projectId().startsWith("local:")) {
            externalActions.openUri(URI.create(
                    "https://modrinth.com/mod/" + selected.projectId()));
        }
    }

    private void openSelectedFile() {
        InstalledMod selected = installedPane.selectedItem();
        if (selected != null) {
            Path path = viewModel.instanceProperty().get().gameDirectory().resolve(selected.relativePath());
            externalActions.openPath(path.getParent());
        }
    }

    private void importLocalJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入本地模组 JAR");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Minecraft 模组 (*.jar)", "*.jar"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            viewModel.importLocalJar(file.toPath());
        }
    }

    private List<String> selectedInstalledIds() {
        return installedPane.selectedIds();
    }

    /** Refreshes the installed list after a file is dropped on the launcher window. */
    public void refreshInstalledMods() {
        viewModel.refreshInstalled();
    }

    @Override
    public void close() {
        searchDebounce.stop();
        viewModel.close();
    }

    private record DetailResult(ModProject project, List<ModVersion> versions) {
    }
}
