package com.ecl.modrinth.ui;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import com.ecl.ui.MainController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Builds and updates the selected mod details pane. */
final class ModDetailsPane {
    private final MainController controller;
    private final ModBrowserViewModel viewModel;
    private final ModExternalActions externalActions;
    private final Supplier<ReleaseChannel> releaseChannel;
    private final Consumer<ModProject> projectSelected;
    private final Consumer<ModVersion> prepareInstall;
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
    private final AtomicLong detailGeneration = new AtomicLong();
    private ModProject detailedProject;
    private String recommendedVersionId = "";

    ModDetailsPane(MainController controller, ModBrowserViewModel viewModel,
                   ModExternalActions externalActions,
                   Supplier<ReleaseChannel> releaseChannel,
                   Consumer<ModProject> projectSelected,
                   Consumer<ModVersion> prepareInstall) {
        this.controller = controller;
        this.viewModel = viewModel;
        this.externalActions = externalActions;
        this.releaseChannel = releaseChannel;
        this.projectSelected = projectSelected;
        this.prepareInstall = prepareInstall;
        bindVersionAndLoadingState();
    }

    Node create() {
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
        installButton.setOnAction(event -> prepareInstall.accept(versionChoice.getValue()));
        configureExternalButton(projectPageButton, ModProject::projectUrl);
        configureExternalButton(sourceButton, ModProject::sourceUrl);
        configureExternalButton(issuesButton, ModProject::issuesUrl);

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

    void showProject(ModProject project) {
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
                    if (error != null || detailedProject != project) {
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

    private void configureExternalButton(Button button, Function<ModProject, java.net.URI> uri) {
        button.getStyleClass().addAll("app-button", "ghost-button");
        button.setDisable(true);
        button.setOnAction(event -> {
            if (detailedProject != null && uri.apply(detailedProject) != null) {
                externalActions.openUri(uri.apply(detailedProject));
            }
        });
    }

    private void bindVersionAndLoadingState() {
        viewModel.loadingProperty().addListener((observable, oldValue, loading) ->
                installButton.setDisable(loading || versionChoice.getValue() == null));
        versionChoice.valueProperty().addListener((observable, oldValue, newValue) -> {
            installButton.setDisable(newValue == null || viewModel.loadingProperty().get());
            renderDetails(detailedProject, newValue);
        });
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
                    if (generation != detailGeneration.get() || detailedProject != project) {
                        return;
                    }
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
            row.setOnMouseClicked(event -> projectSelected.accept(project));
        }
        return row;
    }

    private ModVersion preferredVersion(List<ModVersion> versions) {
        ModInstanceContext context = viewModel.instanceProperty().get();
        return controller.modVersionSelector().selectBestVersion(versions,
                new com.ecl.modrinth.model.ModCompatibility(context.minecraftVersion(), context.loader()),
                releaseChannel.get()).orElse(null);
    }

    private static Label dependencyMessage(String text, boolean error) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(error ? "mod-error" : "status-detail");
        return label;
    }

    private record DetailResult(ModProject project, List<ModVersion> versions) {
    }
}
