package com.ecl.modrinth.ui;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.DependencyType;
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
import javafx.scene.control.Separator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ModBrowserView extends VBox implements AutoCloseable {
    private final MainController controller;
    private final ModBrowserViewModel viewModel;
    private final ModInstallWorkflow installWorkflow;
    private final Consumer<String> statusConsumer;
    private final Label instanceLabel = new Label();
    private final Label emptyLabel = new Label("输入关键词或直接浏览兼容模组");
    private final Label detailTitle = new Label("选择一个模组查看详情");
    private final Label detailMeta = new Label();
    private final TextArea detailBody = new TextArea();
    private final TextArea changelogBody = new TextArea();
    private final VBox dependencyContent = new VBox(8);
    private final TitledPane dependencyPane = new TitledPane();
    private final TitledPane changelogPane = new TitledPane();
    private final Label recommendationLabel = new Label();
    private final ComboBox<ModVersion> versionChoice = new ComboBox<>();
    private final Button installButton = new Button("安装");
    private final Button projectPageButton = new Button("项目主页");
    private final Button sourceButton = new Button("源代码");
    private final Button issuesButton = new Button("问题反馈");
    private final ListView<ModProject> resultList = new ListView<>();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(400));
    private final AtomicLong detailGeneration = new AtomicLong();
    private final ModExternalActions externalActions;
    private Tab installedTab;
    private Tab updatesTab;
    private InstalledModsPane installedPane;
    private ModUpdatesPane updatesPane;
    private boolean updateBatchRunning;
    private String recommendedVersionId = "";

    private ModProject selectedProject;
    private ModProject detailedProject;

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

        VBox detail = createDetailsPane();
        detail.setMinWidth(300);
        SplitPane split = new SplitPane(left, detail);
        split.getStyleClass().add("mod-browser-split");
        split.setDividerPositions(0.54);
        return split;
    }

    private VBox createDetailsPane() {
        detailTitle.getStyleClass().add("section-title");
        detailTitle.setWrapText(true);
        detailMeta.getStyleClass().add("status-detail");
        detailMeta.setWrapText(true);
        detailBody.setEditable(false);
        detailBody.setWrapText(true);
        detailBody.setPrefRowCount(10);
        detailBody.getStyleClass().add("mod-detail-body");
        detailBody.setMinHeight(110);

        dependencyPane.setText("依赖");
        dependencyPane.setContent(dependencyContent);
        dependencyPane.setExpanded(true);
        dependencyPane.getStyleClass().add("mod-detail-section");
        changelogBody.setEditable(false);
        changelogBody.setWrapText(false);
        changelogBody.setPrefRowCount(8);
        changelogBody.getStyleClass().addAll("mod-detail-body", "mod-changelog");
        changelogPane.setText("更新日志");
        changelogPane.setContent(changelogBody);
        changelogPane.setExpanded(false);
        changelogPane.getStyleClass().add("mod-detail-section");
        recommendationLabel.getStyleClass().addAll("mod-version-badge", "mod-recommended");
        recommendationLabel.setVisible(false);
        recommendationLabel.setManaged(false);

        versionChoice.setPromptText("选择兼容版本");
        versionChoice.setMaxWidth(Double.MAX_VALUE);
        versionChoice.setCellFactory(list -> new ModVersionCell(() -> recommendedVersionId));
        versionChoice.setButtonCell(new ModVersionCell(() -> recommendedVersionId));
        installButton.getStyleClass().addAll("app-button", "primary-button");
        installButton.setDisable(true);
        installButton.setOnAction(event -> prepareSelectedVersion());
        projectPageButton.getStyleClass().addAll("app-button", "ghost-button");
        projectPageButton.setDisable(true);
        projectPageButton.setOnAction(event -> {
            if (detailedProject != null && detailedProject.projectUrl() != null) {
                externalActions.openUri(detailedProject.projectUrl());
            }
        });
        sourceButton.getStyleClass().addAll("app-button", "ghost-button");
        sourceButton.setDisable(true);
        sourceButton.setOnAction(event -> {
            if (detailedProject != null && detailedProject.sourceUrl() != null) {
                externalActions.openUri(detailedProject.sourceUrl());
            }
        });
        issuesButton.getStyleClass().addAll("app-button", "ghost-button");
        issuesButton.setDisable(true);
        issuesButton.setOnAction(event -> {
            if (detailedProject != null && detailedProject.issuesUrl() != null) {
                externalActions.openUri(detailedProject.issuesUrl());
            }
        });
        HBox actions = new HBox(8, installButton, projectPageButton, sourceButton, issuesButton);
        HBox.setHgrow(installButton, Priority.ALWAYS);
        installButton.setMaxWidth(Double.MAX_VALUE);

        VBox detailSections = new VBox(10, detailTitle, detailMeta, new Separator(),
                detailBody, dependencyPane, changelogPane);
        ScrollPane detailsScroll = new ScrollPane(detailSections);
        detailsScroll.setFitToWidth(true);
        detailsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailsScroll.getStyleClass().add("mod-detail-scroll");
        VBox.setVgrow(detailsScroll, Priority.ALWAYS);
        HBox versionHeading = new HBox(8, new Label("兼容版本"), recommendationLabel);
        versionHeading.setAlignment(Pos.CENTER_LEFT);
        VBox detail = new VBox(10, detailsScroll, versionHeading, versionChoice, actions);
        detail.setPadding(new Insets(4, 0, 0, 12));
        return detail;
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
            installButton.setDisable(loading || versionChoice.getValue() == null);
            refreshUpdateButtons();
        });
        viewModel.errorMessageProperty().addListener((observable, oldValue, message) -> {
            if (message != null && !message.isBlank()) {
                statusConsumer.accept(message);
            }
        });
        versionChoice.valueProperty().addListener((observable, oldValue, newValue) ->
        {
            installButton.setDisable(newValue == null || viewModel.loadingProperty().get());
            renderDetails(detailedProject, newValue);
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
        detailedProject = project;
        recommendedVersionId = "";
        versionChoice.getItems().clear();
        installButton.setDisable(true);
        projectPageButton.setDisable(project == null);
        sourceButton.setDisable(true);
        issuesButton.setDisable(true);
        if (project == null) {
            detailTitle.setText("选择一个模组查看详情");
            detailMeta.setText("");
            detailBody.clear();
            dependencyContent.getChildren().clear();
            changelogBody.setText(MinimalMarkdown.format(""));
            return;
        }
        detailTitle.setText(project.title());
        detailMeta.setText(project.author() + " · 下载 " + project.downloads());
        showTranslatedDescription(project, null);

        CompletableFuture<ModProject> details = viewModel.loadProjectDetails(project);
        CompletableFuture<List<ModVersion>> versions = viewModel.loadVersions(project);
        details.thenCombine(versions, DetailResult::new)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null || selectedProject != project) {
                        return;
                    }
                    detailedProject = result.project();
                    detailTitle.setText(result.project().title());
                    detailMeta.setText(ModUiFormatter.projectDetails(result.project()));
                    versionChoice.getItems().setAll(result.versions());
                    ModVersion preferred = preferredVersion(result.versions());
                    if (preferred != null) {
                        recommendedVersionId = preferred.id();
                        List<ModVersion> ordered = new ArrayList<>(result.versions());
                        ordered.removeIf(candidate -> candidate.id().equals(preferred.id()));
                        ordered.add(0, preferred);
                        versionChoice.getItems().setAll(ordered);
                        versionChoice.setValue(preferred);
                    } else {
                        renderDetails(result.project(), null);
                    }
                    projectPageButton.setDisable(result.project().projectUrl() == null);
                    sourceButton.setDisable(result.project().sourceUrl() == null);
                    issuesButton.setDisable(result.project().issuesUrl() == null);
                }));
    }

    private void renderDetails(ModProject project, ModVersion version) {
        if (project == null) {
            return;
        }
        long generation = showTranslatedDescription(project, version);
        boolean recommended = version != null && version.id().equals(recommendedVersionId);
        recommendationLabel.setText(recommended ? "推荐" : "");
        recommendationLabel.setVisible(recommended);
        recommendationLabel.setManaged(recommended);
        changelogBody.setText(MinimalMarkdown.format(version == null ? "" : version.changelog()));
        changelogBody.positionCaret(0);
        dependencyContent.getChildren().setAll(new Label(version == null
                ? "请选择兼容版本查看依赖。" : "正在加载依赖…"));
        if (version == null) {
            return;
        }
        viewModel.loadDependencyGroups(version).whenComplete((groups, error) ->
                Platform.runLater(() -> {
                    if (generation != detailGeneration.get()) {
                        return;
                    }
                    if (error != null) {
                        dependencyContent.getChildren().setAll(
                                dependencyMessage("依赖加载失败："
                                        + ModFailureMessages.errorMessage(error), true));
                    } else {
                        renderDependencyGroups(groups);
                    }
                }));
    }

    private long showTranslatedDescription(ModProject project, ModVersion version) {
        long generation = detailGeneration.incrementAndGet();
        detailBody.setText("正在翻译中文简介…");
        ChineseDescriptionService.translate(project.description()).thenAccept(translated ->
                Platform.runLater(() -> {
                    if (generation != detailGeneration.get() || detailedProject != project) return;
                    String summary = translated == null || translated.isBlank()
                            ? project.description() : translated;
                    if (version != null) {
                        summary += "\n\n版本 " + version.versionNumber() + " · " + version.versionType();
                    }
                    detailBody.setText(summary);
                    detailBody.positionCaret(0);
                }));
        return generation;
    }

    private void renderDependencyGroups(List<ModBrowserViewModel.DependencyGroup> groups) {
        dependencyContent.getChildren().clear();
        if (groups.isEmpty()) {
            dependencyContent.getChildren().add(dependencyMessage("此版本没有外部依赖。", false));
            return;
        }
        for (ModBrowserViewModel.DependencyGroup group : groups) {
            Label heading = new Label(ModUiFormatter.dependencyTypeLabel(group.type())
                    + "  " + group.projects().size());
            heading.getStyleClass().add("mod-dependency-heading");
            dependencyContent.getChildren().add(heading);
            for (ModBrowserViewModel.DependencyProject item : group.projects()) {
                dependencyContent.getChildren().add(dependencyItem(item, group.type()));
            }
        }
    }

    private Node dependencyItem(ModBrowserViewModel.DependencyProject item, DependencyType type) {
        ModProject project = item.project();
        ImageView icon = new ImageView(RemoteImageLoader.loadingPlaceholder());
        icon.setFitWidth(32);
        icon.setFitHeight(32);
        icon.setPreserveRatio(true);
        if (project != null && project.iconUrl() != null) {
            RemoteImageLoader.load(project.iconUrl()).thenAccept(image ->
                    Platform.runLater(() -> icon.setImage(image)));
        } else if (project == null) {
            icon.setImage(RemoteImageLoader.brokenPlaceholder());
        }
        String identity = ModUiFormatter.dependencyIdentity(item);
        Label title = new Label(project == null ? identity : project.title());
        title.getStyleClass().add("mod-item-title");
        Label meta = new Label(item.errorMessage().isBlank()
                ? (project == null ? identity : project.description()) : item.errorMessage());
        meta.getStyleClass().add("status-detail");
        meta.setWrapText(true);
        VBox labels = new VBox(2, title, meta);
        HBox row = new HBox(9, icon, labels);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-dependency-item");
        if (type == DependencyType.INCOMPATIBLE) {
            row.getStyleClass().add("mod-dependency-incompatible");
        }
        if (project != null) {
            row.setOnMouseClicked(event -> showProject(project));
        }
        return row;
    }

    private static Label dependencyMessage(String text, boolean error) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(error ? "mod-error" : "status-detail");
        return label;
    }

    private ModVersion preferredVersion(List<ModVersion> versions) {
        ModInstanceContext context = viewModel.instanceProperty().get();
        return controller.modVersionSelector().selectBestVersion(
                        versions,
                        new com.ecl.modrinth.model.ModCompatibility(
                                context.minecraftVersion(), context.loader()),
                        preferredReleaseChannel())
                .orElse(null);
    }

    private ReleaseChannel preferredReleaseChannel() {
        return controller.preferredModReleaseChannel();
    }

    private void prepareSelectedVersion() {
        installWorkflow.prepare(versionChoice.getValue(), "无法准备安装计划");
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
