package com.ecl.ui;

import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


/** Builds content library browser and download dialog. */
final class LauncherContentBrowser {
    private final LauncherUI ui;
    private final ContentSearchController searchController;
    private final ContentDownloadWorkflow downloadWorkflow;

    LauncherContentBrowser(LauncherUI ui) {
        this.ui = ui;
        this.searchController = new ContentSearchController(ui);
        this.downloadWorkflow = new ContentDownloadWorkflow(ui);
    }

    Node createContentLibraryBrowser(ContentTarget target) {
        List<String> profileIds = downloadWorkflow.availableContentProfiles(target);
        if (profileIds.isEmpty()) {
            Button choose = ui.createActionButton("返回首页选择实例", "primary-button",
                    () -> ui.setActiveView(AppView.HOME));
            return ui.createSurface(target.title, "还没有可用的 Minecraft 实例",
                    ui.createBodyText("请先安装或选择一个游戏版本，下载后会自动导入该实例的 "
                            + ("shader".equals(target.projectType) ? "shaderpacks" : "resourcepacks") + " 目录。"),
                    choose);
        }
        String activeProfile = ui.getSelectedVersion();
        String initialProfile = profileIds.contains(activeProfile) ? activeProfile : profileIds.getFirst();
        ContentInstance initialInstance = downloadWorkflow.resolveContentInstance(initialProfile);
        Label eyebrow = new Label("MODRINTH / "
                + target.projectType.toUpperCase(java.util.Locale.ROOT));
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label(target.title);
        title.getStyleClass().add("content-library-section-title");
        Label description = new Label("modpack".equals(target.projectType)
                ? "选择兼容整合包，安装为独立实例后立即启动"
                : "搜索、选择兼容版本并直接安装到当前实例");
        description.getStyleClass().add("status-detail");
        VBox heading = new VBox(4, eyebrow, title, description);
        ComboBox<String> targetProfileCombo = new ComboBox<>();
        targetProfileCombo.getItems().setAll(profileIds);
        targetProfileCombo.setValue(initialProfile);
        targetProfileCombo.setCellFactory(list -> ui.createVersionCell());
        targetProfileCombo.setButtonCell(ui.createVersionCell());
        targetProfileCombo.setVisibleRowCount(14);
        ui.applyFieldStyle(targetProfileCombo);
        targetProfileCombo.setMaxWidth(Double.MAX_VALUE);
        TextField searchField = new TextField();
        searchField.setPromptText(target.searchHint);
        ui.applyFieldStyle(searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button searchButton = ui.createActionButton("搜索", "primary-button", () -> { });
        HBox searchBar = new HBox(8, searchField, searchButton);
        ListView<ContentProject> resultList = new ListView<>();
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPrefHeight(330);
        resultList.setPlaceholder(new Label("没有找到兼容内容"));
        resultList.setCellFactory(list -> searchController.createContentProjectCell(target));
        Label projectDescription = new Label("选择一个项目查看简介和兼容版本");
        projectDescription.getStyleClass().add("content-library-description");
        projectDescription.setWrapText(true);
        projectDescription.setMinHeight(86);
        ComboBox<ContentVersion> versionComboBox = new ComboBox<>();
        versionComboBox.setPromptText("选择具体版本");
        versionComboBox.setDisable(true);
        versionComboBox.setMaxWidth(Double.MAX_VALUE);
        ui.applyFieldStyle(versionComboBox);
        Label targetLabel = new Label();
        targetLabel.getStyleClass().add("content-library-target");
        targetLabel.setWrapText(true);
        downloadWorkflow.updateContentTargetLabel(target, initialInstance, targetLabel);
        Label status = new Label("正在加载 Modrinth 热门" + target.title + "…");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("download-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        Button downloadButton = ui.createActionButton("modpack".equals(target.projectType)
                ? "安装并启动" : "下载并安装", "primary-button", () -> { });
        downloadButton.setDisable(true);
        Button folderButton = ui.createActionButton("打开安装目录", "secondary-button", () -> {
            ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
            File directory = target.folderResolver.apply(inst.profileId());
            try {
                ui.ensureDirectory(directory);
                ui.openLocalFolder(directory, target.title + "目录");
            } catch (IOException error) {
                status.setText("无法创建目录: " + ui.cleanMessage(error));
            }
        });
        HBox actions = new HBox(8, downloadButton, folderButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        AtomicLong searchGeneration = new AtomicLong();
        AtomicLong versionGeneration = new AtomicLong();
        AtomicLong downloadGeneration = new AtomicLong();
        AtomicLong descriptionGeneration = new AtomicLong();
        resultList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            long selectedDescriptionGeneration = descriptionGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            versionComboBox.getItems().clear();
            versionComboBox.setDisable(selected == null);
            downloadButton.setDisable(true);
            projectDescription.setText(selected == null
                    ? "选择一个项目查看简介和兼容版本"
                    : "正在翻译中文简介…");
            if (selected != null) {
                searchController.setTranslatedProjectDescription(selected, projectDescription,
                        descriptionGeneration, selectedDescriptionGeneration);
                searchController.loadProjectVersions(target, selected,
                        downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue()),
                        versionComboBox, status, downloadButton, versionGeneration);
            }
        });
        versionComboBox.valueProperty().addListener((observable, oldValue, selected) ->
                downloadButton.setDisable(selected == null
                        || resultList.getSelectionModel().getSelectedItem() == null));
        Runnable search = () -> searchController.searchModrinthContent(target,
                downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue()),
                searchField, resultList, status, searchButton, downloadButton, searchGeneration);
        searchButton.setOnAction(event -> search.run());
        searchField.setOnAction(event -> search.run());
        targetProfileCombo.setOnAction(event -> {
            ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
            downloadWorkflow.updateContentTargetLabel(target, inst, targetLabel);
            versionGeneration.incrementAndGet();
            versionComboBox.getItems().clear();
            versionComboBox.setDisable(true);
            resultList.getItems().clear();
            downloadButton.setDisable(true);
            search.run();
        });
        downloadButton.setOnAction(event -> {
            ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
            File directory = target.folderResolver.apply(inst.profileId());
            try {
                ui.ensureDirectory(directory);
            } catch (IOException error) {
                status.setText("无法创建目录: " + ui.cleanMessage(error));
                return;
            }
            downloadWorkflow.downloadSelectedContent(target,
                    resultList.getSelectionModel().getSelectedItem(),
                    versionComboBox.getValue(), inst, directory, status, progress,
                    searchButton, downloadButton, targetProfileCombo, downloadGeneration);
        });
        VBox browser = new VBox(12, heading, targetProfileCombo, searchBar, resultList,
                projectDescription, versionComboBox, targetLabel, status, progress, actions);
        browser.getStyleClass().addAll("surface", "content-library-browser");
        browser.setFillWidth(true);
        search.run();
        return browser;
    }

}
