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
import com.ecl.modrinth.service.ResolvedMod;
import com.ecl.modrinth.service.SequentialBatchRunner;
import com.ecl.modrinth.transaction.ModInstallationPlan;
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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
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

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ModBrowserView extends VBox implements AutoCloseable {
    private final MainController controller;
    private final ModBrowserViewModel viewModel;
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
    private final ListView<InstalledMod> installedList = new ListView<>();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(400));
    private final AtomicLong detailGeneration = new AtomicLong();
    private Tab installedTab;
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
        this.viewModel = new ModBrowserViewModel(
                controller.metadataProvider(ContentSource.MODRINTH),
                controller.modDependencyResolver(),
                controller.installationPlanBuilder(),
                controller.modInstallationService(),
                controller.modManagementService(),
                controller.localModScanner(),
                controller.modUpdateService());
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

        Label eyebrow = new Label("MODRINTH / MODS");
        eyebrow.getStyleClass().add("eyebrow");
        instanceLabel.setText(instanceText(context));
        instanceLabel.getStyleClass().add("mod-instance-badge");
        Label scope = new Label("搜索与安装始终锁定当前实例的 Minecraft 版本和加载器");
        scope.getStyleClass().add("status-detail");
        scope.setWrapText(true);
        VBox heading = new VBox(4, eyebrow, instanceLabel, scope);

        Tab browserTab = new Tab("浏览模组", createBrowserPane());
        installedTab = new Tab("已安装", createInstalledPane());
        browserTab.setClosable(false);
        installedTab.setClosable(false);
        TabPane tabs = new TabPane(browserTab, installedTab);
        tabs.getStyleClass().add("mod-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab == installedTab) {
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
        Button cancel = button("取消", "ghost-button");
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
        sort.setButtonCell(enumCell());
        sort.setCellFactory(list -> enumCell());
        sort.setPrefWidth(122);

        ComboBox<CategoryChoice> category = new ComboBox<>();
        category.getItems().setAll(
                new CategoryChoice("全部分类", ""),
                new CategoryChoice("性能优化", "optimization"),
                new CategoryChoice("科技", "technology"),
                new CategoryChoice("冒险", "adventure"),
                new CategoryChoice("魔法", "magic"),
                new CategoryChoice("实用工具", "utility"),
                new CategoryChoice("装饰", "decoration"));
        category.getSelectionModel().selectFirst();
        category.setPrefWidth(126);
        category.setOnAction(event -> {
            CategoryChoice selected = category.getValue();
            viewModel.setCategory(selected == null ? "" : selected.id());
            viewModel.search(false);
        });

        ComboBox<ModMetadataProvider> source = new ComboBox<>();
        source.getItems().setAll(controller.metadataProviders());
        source.setCellFactory(list -> providerCell());
        source.setButtonCell(providerCell());
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

        Button searchButton = button("搜索", "primary-button");
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
        resultList.setCellFactory(list -> new ProjectCell());
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPlaceholder(emptyLabel);
        resultList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showProject(newValue));
        VBox.setVgrow(resultList, Priority.ALWAYS);

        Button loadMore = button("加载更多", "ghost-button");
        loadMore.setMaxWidth(Double.MAX_VALUE);
        loadMore.setOnAction(event -> viewModel.search(true));
        Button retry = button("重试", "secondary-button");
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
        versionChoice.setCellFactory(list -> versionCell());
        versionChoice.setButtonCell(versionCell());
        installButton.getStyleClass().addAll("app-button", "primary-button");
        installButton.setDisable(true);
        installButton.setOnAction(event -> prepareSelectedVersion());
        projectPageButton.getStyleClass().addAll("app-button", "ghost-button");
        projectPageButton.setDisable(true);
        projectPageButton.setOnAction(event -> {
            if (detailedProject != null && detailedProject.projectUrl() != null) {
                openUri(detailedProject.projectUrl());
            }
        });
        sourceButton.getStyleClass().addAll("app-button", "ghost-button");
        sourceButton.setDisable(true);
        sourceButton.setOnAction(event -> {
            if (detailedProject != null && detailedProject.sourceUrl() != null) {
                openUri(detailedProject.sourceUrl());
            }
        });
        issuesButton.getStyleClass().addAll("app-button", "ghost-button");
        issuesButton.setDisable(true);
        issuesButton.setOnAction(event -> {
            if (detailedProject != null && detailedProject.issuesUrl() != null) {
                openUri(detailedProject.issuesUrl());
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
        installedList.setItems(viewModel.installedMods());
        installedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        installedList.setCellFactory(list -> new InstalledCell());
        installedList.setPlaceholder(new Label("尚未发现模组，点击“重新扫描”同步 mods 目录"));
        VBox.setVgrow(installedList, Priority.ALWAYS);

        Button scan = button("重新扫描", "secondary-button");
        scan.setOnAction(event -> viewModel.rescan());
        Button enable = button("启用", "ghost-button");
        enable.setOnAction(event -> operateSelected(true));
        Button disable = button("禁用", "ghost-button");
        disable.setOnAction(event -> operateSelected(false));
        Button uninstall = button("卸载", "danger-button");
        uninstall.setOnAction(event -> uninstallSelected());
        Button updates = button("检查更新", "secondary-button");
        updates.setOnAction(event -> viewModel.checkUpdates(preferredReleaseChannel()));
        Button updateSelected = button("更新选中", "primary-button");
        updateSelected.setOnAction(event -> updateSelected());
        Button updateAll = button("全部更新", "primary-button");
        updateAll.setOnAction(event -> updateAll());
        Button history = button("历史版本", "ghost-button");
        history.setOnAction(event -> chooseHistoryVersion());
        Button project = button("项目页面", "ghost-button");
        project.setOnAction(event -> openSelectedProject());
        Button file = button("文件位置", "ghost-button");
        file.setOnAction(event -> openSelectedFile());
        Button importJar = button("导入 JAR", "secondary-button");
        importJar.setOnAction(event -> importLocalJar());

        HBox rowOne = new HBox(8, scan, enable, disable, uninstall, importJar);
        HBox rowTwo = new HBox(8, updates, updateSelected, updateAll, history, project, file);
        VBox pane = new VBox(10, installedList, rowOne, rowTwo);
        pane.setPadding(new Insets(10, 0, 0, 0));
        return pane;
    }

    private void bindState() {
        viewModel.loadingProperty().addListener((observable, oldValue, loading) -> {
            resultList.setDisable(loading);
            installButton.setDisable(loading || versionChoice.getValue() == null);
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
            installedList.refresh();
        });
        viewModel.searchResults().addListener((ListChangeListener<ModProject>) change ->
                RemoteImageLoader.prefetch(viewModel.searchResults().stream()
                        .map(ModProject::iconUrl).filter(java.util.Objects::nonNull).toList()));
        viewModel.updateCountProperty().addListener((observable, oldValue, newValue) -> {
            int count = newValue == null ? 0 : newValue.intValue();
            installedTab.setText(count > 0 ? "已安装 (" + count + ")" : "已安装");
            installedList.refresh();
            resultList.refresh();
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
                    detailMeta.setText(formatDetails(result.project()));
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
                                dependencyMessage("依赖加载失败：" + errorMessage(error), true));
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
            Label heading = new Label(dependencyTypeLabel(group.type())
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
        String identity = dependencyIdentity(item);
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

    private static String dependencyIdentity(ModBrowserViewModel.DependencyProject item) {
        String projectId = item.dependency().projectId();
        if (projectId != null && !projectId.isBlank()) {
            return projectId;
        }
        String versionId = item.dependency().versionId();
        return versionId == null || versionId.isBlank() ? "未知依赖" : versionId;
    }

    private static Label dependencyMessage(String text, boolean error) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(error ? "mod-error" : "status-detail");
        return label;
    }

    private static String dependencyTypeLabel(DependencyType type) {
        return switch (type) {
            case REQUIRED -> "必需依赖";
            case OPTIONAL -> "可选依赖";
            case EMBEDDED -> "内嵌依赖";
            case INCOMPATIBLE -> "不兼容";
            case UNKNOWN -> "其他依赖";
        };
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
        ModVersion version = versionChoice.getValue();
        if (version == null) {
            return;
        }
        viewModel.preparePlan(version, Set.of(), preferredReleaseChannel()).whenComplete((plan, error) -> {
            Platform.runLater(() -> handlePreparedPlan(
                    version, plan, error, "无法准备安装计划"));
        });
    }

    private void handlePreparedPlan(
            ModVersion version,
            ModInstallationPlan plan,
            Throwable error,
            String failureHeader
    ) {
        if (error != null) {
            showPlanFailure(failureHeader, error);
            return;
        }
        previewPlan(version, plan);
    }

    private void showPlanFailure(String header, Throwable error) {
        if (isCancellation(error)) {
            return;
        }
        Alert dialog = new Alert(Alert.AlertType.ERROR);
        dialog.setTitle("模组安装失败");
        dialog.setHeaderText(header);
        dialog.setContentText("失败原因：" + planFailureReason(error));
        dialog.getDialogPane().setPrefWidth(560);
        dialog.showAndWait();
    }

    static String planFailureReason(Throwable error) {
        Throwable cause = unwrap(error);
        return cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? "操作失败，请查看日志获取详细信息"
                : cause.getMessage();
    }

    private static boolean isCancellation(Throwable error) {
        return unwrap(error) instanceof CancellationException;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void previewPlan(ModVersion version, ModInstallationPlan plan) {
        ButtonType install = new ButtonType("确认安装", ButtonBar.ButtonData.OK_DONE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("安装计划");
        dialog.setHeaderText(plan.installable()
                ? "将安装 " + plan.files().size() + " 个文件"
                : "安装计划存在冲突");
        dialog.getButtonTypes().setAll(install, ButtonType.CANCEL);

        VBox content = new VBox(8);
        content.getChildren().add(new Label("目标目录: " + plan.instance().modsDirectory()));
        content.getChildren().add(new Label("下载大小: " + formatBytes(plan.totalDownloadSize())));
        for (var file : plan.files()) {
            Label label = new Label((file.dependency() ? "依赖  " : "主模组  ")
                    + file.version().name() + " · " + file.version().versionNumber()
                    + " · " + formatBytes(file.file().size()));
            label.setWrapText(true);
            content.getChildren().add(label);
        }
        List<CheckBox> optionalChoices = new ArrayList<>();
        if (!plan.optionalDependencies().isEmpty()) {
            content.getChildren().add(new Separator());
            content.getChildren().add(new Label("可选依赖"));
            for (ResolvedMod optional : plan.optionalDependencies()) {
                CheckBox check = new CheckBox(optional.version().name()
                        + " · " + optional.version().versionNumber());
                check.setUserData(optional);
                optionalChoices.add(check);
                content.getChildren().add(check);
            }
        }
        for (var conflict : plan.conflicts()) {
            Label label = new Label("冲突: " + conflict.message());
            label.getStyleClass().add("mod-error");
            label.setWrapText(true);
            content.getChildren().add(label);
        }
        for (String warning : plan.warnings()) {
            Label label = new Label("提示: " + warning);
            label.getStyleClass().add("status-detail");
            label.setWrapText(true);
            content.getChildren().add(label);
        }
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().lookupButton(install).setDisable(!plan.installable());
        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.orElse(ButtonType.CANCEL) != install) {
            return;
        }
        Set<String> selectedOptional = new LinkedHashSet<>();
        optionalChoices.stream().filter(CheckBox::isSelected).forEach(check -> {
            ResolvedMod optional = (ResolvedMod) check.getUserData();
            selectedOptional.add(optional.version().projectId());
        });
        if (selectedOptional.isEmpty()) {
            viewModel.install(plan);
        } else {
            viewModel.preparePlan(
                    version, selectedOptional, preferredReleaseChannel()).whenComplete((expanded, error) -> {
                Platform.runLater(() -> {
                    if (error != null) {
                        showPlanFailure("无法解析所选可选依赖", error);
                        return;
                    }
                    viewModel.install(expanded);
                });
            });
        }
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
        InstalledMod selected = installedList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            viewModel.applyUpdate(selected.projectId());
        }
    }

    private void updateAll() {
        List<ModUpdate> updates = viewModel.availableUpdates();
        if (updates.isEmpty()) {
            statusConsumer.accept("没有可用更新");
            return;
        }
        SequentialBatchRunner.run(
                updates,
                update -> viewModel.applyUpdate(update.installedMod().projectId()))
                .thenAccept(result -> Platform.runLater(() -> {
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

    private void chooseHistoryVersion() {
        InstalledMod selected = installedList.getSelectionModel().getSelectedItem();
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
                            Platform.runLater(() -> handlePreparedPlan(
                                    version, plan, failure, "无法准备安装计划"));
                        });
                });
            });
        });
    }

    private void openSelectedProject() {
        InstalledMod selected = installedList.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.projectId().startsWith("local:")) {
            openUri(URI.create("https://modrinth.com/mod/" + selected.projectId()));
        }
    }

    private void openSelectedFile() {
        InstalledMod selected = installedList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Path path = viewModel.instanceProperty().get().gameDirectory().resolve(selected.relativePath());
            openPath(path.getParent());
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
        return installedList.getSelectionModel().getSelectedItems().stream()
                .map(InstalledMod::projectId).distinct().toList();
    }

    private void openUri(URI uri) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (IOException error) {
            statusConsumer.accept("无法打开项目页面: " + error.getMessage());
        }
    }

    private void openPath(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (IOException error) {
            statusConsumer.accept("无法打开文件位置: " + error.getMessage());
        }
    }

    private String formatDetails(ModProject project) {
        List<String> parts = new ArrayList<>();
        if (!project.author().isBlank()) parts.add(project.author());
        parts.add("下载 " + project.downloads());
        if (!project.license().isBlank()) parts.add(project.license());
        if (!project.clientSide().isBlank()) parts.add("客户端 " + project.clientSide());
        if (!project.serverSide().isBlank()) parts.add("服务端 " + project.serverSide());
        return String.join(" · ", parts);
    }

    private String instanceText(ModInstanceContext context) {
        return context.minecraftVersion() + " · " + loaderName(context.loaderName())
                + " · " + context.profileId();
    }

    private static String loaderName(String loader) {
        return switch (loader) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> "原版";
        };
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static Button button(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", styleClass, "compact-button");
        return button;
    }

    private static ListCell<ModSearchIndex> enumCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ModSearchIndex item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item) {
                    case RELEVANCE -> "相关度";
                    case DOWNLOADS -> "下载量";
                    case FOLLOWS -> "关注数";
                    case NEWEST -> "最新发布";
                    case UPDATED -> "最近更新";
                });
            }
        };
    }

    private ListCell<ModVersion> versionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ModVersion item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label version = new Label(item.versionNumber());
                version.getStyleClass().add("mod-version-number");
                HBox badges = new HBox(5, version,
                        versionBadge(channelLabel(item.versionType()),
                                "mod-channel-" + normalizedChannel(item.versionType())));
                item.loaders().stream().limit(2).forEach(loader ->
                        badges.getChildren().add(versionBadge(loaderDisplay(loader), "mod-loader-badge")));
                if (item.featured() || item.id().equals(recommendedVersionId)) {
                    badges.getChildren().add(versionBadge("★ 推荐", "mod-recommended"));
                }
                badges.setAlignment(Pos.CENTER_LEFT);
                setGraphic(badges);
            }
        };
    }

    private static ListCell<ModMetadataProvider> providerCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ModMetadataProvider item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item.source()) {
                    case MODRINTH -> "Modrinth";
                    case CURSEFORGE -> "CurseForge";
                });
            }
        };
    }

    private static Label versionBadge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("mod-version-badge", styleClass);
        return label;
    }

    private static String normalizedChannel(String type) {
        String value = type == null ? "release" : type.toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "beta" -> "beta";
            case "alpha" -> "alpha";
            default -> "release";
        };
    }

    private static String channelLabel(String type) {
        return switch (normalizedChannel(type)) {
            case "beta" -> "Beta";
            case "alpha" -> "Alpha";
            default -> "Release";
        };
    }

    private static String loaderDisplay(String loader) {
        if (loader == null || loader.isBlank()) {
            return "通用";
        }
        return switch (loader.toLowerCase(java.util.Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "quilt" -> "Quilt";
            default -> loader;
        };
    }

    @Override
    public void close() {
        searchDebounce.stop();
        viewModel.close();
    }

    private final class ProjectCell extends ListCell<ModProject> {
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

    private final class InstalledCell extends ListCell<InstalledMod> {
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
                    + " · " + formatBytes(mod.fileSize())
                    + (mod.dependency() ? " · 依赖" : "")
                    + (mod.requiredByProjectId().isBlank() ? "" : " · 被 " + mod.requiredByProjectId() + " 依赖")
                    + (mod.installedAt() == null ? "" : " · " + mod.installedAt().toString().substring(0, 10))
                    + (mod.projectId().startsWith("local:") ? " · 本地/未知来源" : " · Modrinth")
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

    private record DetailResult(ModProject project, List<ModVersion> versions) {
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = unwrap(error);
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "未知错误" : cause.getMessage();
    }

    private record CategoryChoice(String label, String id) {
        @Override
        public String toString() {
            return label;
        }
    }
}
