package com.ecl.ui;

import com.ecl.download.ContentDownloader;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.provider.ContentSource;
import com.ecl.modrinth.ui.ChineseDescriptionService;
import com.ecl.modrinth.ui.RemoteImageLoader;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.ecl.util.TextUtil.formatCount;

/** Owns Modrinth/CurseForge search, version loading, and project-list rendering. */
final class ContentSearchController {
    private final LauncherUI ui;

    ContentSearchController(LauncherUI ui) {
        this.ui = ui;
    }

    void loadProjectVersions(
            ContentSource source,
            ContentTarget target,
            ContentProject project,
            ContentInstance instance,
            ComboBox<ContentVersion> projectVersionCombo,
            Label dialogStatus,
            Button importBtn,
            AtomicLong versionGeneration
    ) {
        long generation = versionGeneration.incrementAndGet();
        String loader = target.usesLoader() ? instance.loader() : null;
        projectVersionCombo.setDisable(true);
        importBtn.setDisable(true);
        dialogStatus.setText("正在加载 " + project.getTitle() + " 的兼容版本...");

        ui.runAsync("ecl-load-" + source.id() + "-versions", () -> {
            try {
                List<ContentVersion> versions =
                        ui.controller.contentDownloader(source).listProjectVersions(
                                project, instance.minecraftVersion(), loader).stream()
                                .filter(version -> ui.controller.preferredModReleaseChannel()
                                        .allows(version.versionType()))
                                .toList();
                Platform.runLater(() -> {
                    if (generation != versionGeneration.get()) {
                        return;
                    }
                    projectVersionCombo.getItems().setAll(versions);
                    projectVersionCombo.setDisable(versions.isEmpty());
                    if (versions.isEmpty()) {
                        dialogStatus.setText("当前发布通道没有兼容 "
                                + instance.minecraftVersion() + " / "
                                + ContentDownloadWorkflow.loaderDisplayName(loader) + " 的版本");
                        importBtn.setDisable(true);
                        return;
                    }
                    projectVersionCombo.getSelectionModel().selectFirst();
                    dialogStatus.setText("已加载 " + versions.size() + " 个兼容版本，请确认后导入");
                    importBtn.setDisable(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (generation != versionGeneration.get()) {
                        return;
                    }
                    projectVersionCombo.getItems().clear();
                    projectVersionCombo.setDisable(true);
                    importBtn.setDisable(true);
                    dialogStatus.setText("版本加载失败: " + ui.cleanMessage(error));
                });
            }
        });
    }

    void searchModrinthContent(ContentSource source, ContentTarget target,
                               ContentInstance instance, TextField searchField,
                               ListView<ContentProject> resultList, Label dialogStatus,
                               Button searchBtn, Button importBtn, AtomicLong searchGeneration) {
        long generation = searchGeneration.incrementAndGet();
        String query = searchField.getText();
        String gameVersion = instance.minecraftVersion();
        String loader = target.usesLoader() ? instance.loader() : null;
        String loaderLabel = loader == null ? "" : " / " + loader;
        String sourceName = source == ContentSource.CURSEFORGE ? "CurseForge" : "Modrinth";
        boolean officialList = query == null || query.trim().isBlank();

        searchBtn.setDisable(true);
        importBtn.setDisable(true);
        resultList.getItems().clear();
        dialogStatus.setText(officialList
                ? "正在加载 " + sourceName + " " + target.title + "下载列表..."
                : "正在搜索 " + gameVersion + loaderLabel + " 的兼容" + target.title + "...");
        ui.setStatus(officialList ? "正在加载官网列表" : "正在搜索" + target.title,
                officialList ? sourceName + " " + target.title + " · 下载量排序" : query.trim());

        ui.runAsync("ecl-search-" + source.id() + "-" + target.projectType, () -> {
            try {
                ContentDownloader contentDownloader = ui.controller.contentDownloader(source);
                List<ContentProject> projects = officialList
                        ? contentDownloader.listOfficialProjects(
                                gameVersion, target.projectType, loader, 24)
                        : contentDownloader.searchProjects(
                                query, gameVersion, target.projectType, loader, 24);
                Platform.runLater(() -> {
                    if (generation != searchGeneration.get()) {
                        return;
                    }
                    resultList.getItems().setAll(projects);
                    RemoteImageLoader.prefetch(projects.stream()
                            .map(ContentProject::getIconUrl)
                            .filter(java.util.Objects::nonNull)
                            .map(this::safeUri)
                            .filter(java.util.Objects::nonNull)
                            .toList());
                    if (!projects.isEmpty()) {
                        resultList.getSelectionModel().select(0);
                    }
                    dialogStatus.setText(projects.isEmpty()
                            ? "没有找到兼容 " + gameVersion + loaderLabel + " 的" + target.title + "。"
                            : (officialList ? "已加载 " + sourceName + " 列表 " : "找到 ")
                                    + projects.size() + " 个结果，选择一个后下载。");
                    ui.setStatus(officialList ? "官网列表已加载" : target.title + "搜索完成",
                            projects.isEmpty() ? "没有找到匹配结果。" : projects.size() + " 个兼容结果。");
                    searchBtn.setDisable(false);
                    importBtn.setDisable(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (generation != searchGeneration.get()) {
                        return;
                    }
                    String message = ui.cleanMessage(e);
                    dialogStatus.setText("搜索失败: " + message);
                    ui.setStatus(target.title + "搜索失败", message);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(true);
                });
            }
        });
    }

    ListCell<ContentProject> createContentProjectCell(ContentTarget target) {
        return new ListCell<>() {
            @Override
            protected void updateItem(ContentProject project, boolean empty) {
                super.updateItem(project, empty);
                setText(null);
                if (empty || project == null) {
                    setGraphic(null);
                    return;
                }
                ImageView cover = new ImageView(RemoteImageLoader.loadingPlaceholder());
                cover.setFitWidth(54);
                cover.setFitHeight(54);
                cover.setPreserveRatio(true);
                URI iconUri = safeUri(project.getIconUrl());
                if (iconUri == null) {
                    cover.setImage(RemoteImageLoader.brokenPlaceholder());
                } else {
                    RemoteImageLoader.load(iconUri).thenAccept(image -> Platform.runLater(() -> {
                        if (getItem() == project) cover.setImage(image);
                    }));
                }
                Label title = new Label(project.getTitle());
                title.getStyleClass().add("mod-item-title");
                Label summary = new Label("modpack".equals(target.projectType)
                        ? "正在翻译简介…" : project.getDescription());
                summary.getStyleClass().add("content-project-summary");
                summary.setWrapText(true);
                summary.setMaxWidth(620);
                if ("modpack".equals(target.projectType)) {
                    ChineseDescriptionService.translate(project.getDescription()).thenAccept(translated ->
                            Platform.runLater(() -> {
                                if (getItem() == project) {
                                    summary.setText(translated == null || translated.isBlank()
                                            ? project.getDescription() : translated);
                                }
                            }));
                }
                String author = project.getAuthor() == null || project.getAuthor().isBlank()
                        ? "Modrinth" : project.getAuthor();
                Label meta = new Label(author + " · 下载 " + formatCount(project.getDownloads()));
                meta.getStyleClass().add("mod-item-meta");
                VBox labels = new VBox(3, title, summary, meta);
                HBox row = new HBox(12, cover, labels);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(row);
            }
        };
    }

    ComboBox<ContentSource> createContentSourceCombo() {
        ComboBox<ContentSource> combo = new ComboBox<>();
        combo.getItems().setAll(ContentSource.MODRINTH, ContentSource.CURSEFORGE);
        combo.setValue(ContentSource.MODRINTH);
        combo.setPrefWidth(132);
        combo.setCellFactory(list -> contentSourceCell());
        combo.setButtonCell(contentSourceCell());
        ui.applyFieldStyle(combo);
        return combo;
    }

    private static ListCell<ContentSource> contentSourceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ContentSource item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item) {
                    case MODRINTH -> "Modrinth";
                    case CURSEFORGE -> "CurseForge";
                });
            }
        };
    }

    private URI safeUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    void setTranslatedProjectDescription(
            ContentProject project,
            Label descriptionLabel,
            AtomicLong descriptionGeneration,
            long expectedGeneration
    ) {
        ChineseDescriptionService.translate(project.getDescription()).thenAccept(translated ->
                Platform.runLater(() -> {
                    if (descriptionLabel.getScene() == null
                            || descriptionGeneration.get() != expectedGeneration) return;
                    String description = translated == null || translated.isBlank()
                            ? project.getDescription() : translated;
                    descriptionLabel.setText(project.getTitle()
                            + "\n下载量: " + formatCount(project.getDownloads())
                            + "    关注: " + formatCount(project.getFollows())
                            + "\n\n" + description);
                }));
    }
}
