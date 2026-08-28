package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.provider.ContentSource;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javafx.geometry.Insets;

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
        Label eyebrow = new Label("MODRINTH + CURSEFORGE / "
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
        ComboBox<ContentSource> sourceCombo = searchController.createContentSourceCombo();
        HBox searchBar = new HBox(8, sourceCombo, searchField, searchButton);
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
        AtomicLong activeDownloadGeneration = new AtomicLong();
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
                searchController.loadProjectVersions(sourceCombo.getValue(), target, selected,
                        downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue()),
                        versionComboBox, status, downloadButton, versionGeneration);
            }
        });
        versionComboBox.valueProperty().addListener((observable, oldValue, selected) ->
                downloadButton.setDisable(selected == null
                        || resultList.getSelectionModel().getSelectedItem() == null));
        Runnable search = () -> searchController.searchModrinthContent(sourceCombo.getValue(), target,
                downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue()),
                searchField, resultList, status, searchButton, downloadButton, searchGeneration);
        searchButton.setOnAction(event -> search.run());
        searchField.setOnAction(event -> search.run());
        sourceCombo.setOnAction(event -> {
            versionGeneration.incrementAndGet();
            versionComboBox.getItems().clear();
            versionComboBox.setDisable(true);
            resultList.getItems().clear();
            downloadButton.setDisable(true);
            search.run();
        });
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
            downloadWorkflow.downloadSelectedContent(sourceCombo.getValue(), target,
                    resultList.getSelectionModel().getSelectedItem(),
                    versionComboBox.getValue(), inst, directory, status, progress,
                    searchButton, downloadButton, targetProfileCombo, downloadGeneration,
                    activeDownloadGeneration);
        });
        VBox browser = new VBox(12, heading, targetProfileCombo, searchBar, resultList,
                projectDescription, versionComboBox, targetLabel, status, progress, actions);
        browser.getStyleClass().addAll("surface", "content-library-browser");
        browser.setFillWidth(true);
        search.run();
        return browser;
    }

    void showContentDownloadDialog(ContentTarget target) {
        List<String> profileIds = downloadWorkflow.availableContentProfiles(target);
        if (profileIds.isEmpty()) {
            String detail = target.usesLoader()
                    ? "请选择并安装 Fabric、Forge、NeoForge 或 Quilt 实例。"
                    : "请先在启动器中加载可用的 Minecraft 版本。";
            ui.setStatus("没有可用目标实例", detail);
            if (target.usesLoader()) {
                ui.showLoaderInstallDialog();
            }
            return;
        }
        String activeProfile = ui.getSelectedVersion();
        String initialProfile = profileIds.contains(activeProfile) ? activeProfile : profileIds.getFirst();
        ContentInstance initialInstance = downloadWorkflow.resolveContentInstance(initialProfile);
        Stage dialog = new Stage();
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("下载 " + target.title + " - "
                + ui.versionManager.getVersionDisplayName(initialInstance.profileId()));
        ui.applyWindowIcon(dialog);
        ComboBox<String> targetProfileCombo = new ComboBox<>();
        targetProfileCombo.getItems().setAll(profileIds);
        targetProfileCombo.setValue(initialProfile);
        targetProfileCombo.setCellFactory(list -> ui.createVersionCell());
        targetProfileCombo.setButtonCell(ui.createVersionCell());
        targetProfileCombo.setVisibleRowCount(14);
        ui.applyFieldStyle(targetProfileCombo);
        TextField searchField = new TextField();
        searchField.setPromptText(target.searchHint);
        ui.applyFieldStyle(searchField);
        ComboBox<String> loaderCombo = new ComboBox<>();
        if (target.usesLoader()) {
            loaderCombo.getItems().setAll(initialInstance.loader());
            loaderCombo.setValue(initialInstance.loader());
            loaderCombo.setDisable(true);
        }
        ui.applyFieldStyle(loaderCombo);
        LauncherUiFactory.setVisible(loaderCombo, target.usesLoader());
        Button searchBtn = new Button("搜索");
        searchBtn.getStyleClass().addAll("app-button", "secondary-button");
        HBox targetBar = new HBox(10, targetProfileCombo, loaderCombo);
        HBox.setHgrow(targetProfileCombo, Priority.ALWAYS);
        loaderCombo.setPrefWidth(132);
        ComboBox<ContentSource> sourceCombo = searchController.createContentSourceCombo();
        HBox searchBar = new HBox(10, sourceCombo, searchField, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        ListView<ContentProject> resultList = new ListView<>();
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPrefHeight(220);
        resultList.setCellFactory(list -> searchController.createContentProjectCell(target));
        ComboBox<ContentVersion> projectVersionCombo = new ComboBox<>();
        projectVersionCombo.setPromptText("选择具体版本");
        projectVersionCombo.setDisable(true);
        ui.applyFieldStyle(projectVersionCombo);
        Label descriptionLabel = new Label("正在加载 Modrinth 下载列表...");
        descriptionLabel.getStyleClass().add("status-detail");
        descriptionLabel.setWrapText(true);
        Label targetLabel = new Label();
        targetLabel.getStyleClass().add("footer-text");
        targetLabel.setWrapText(true);
        downloadWorkflow.updateContentTargetLabel(target, initialInstance, targetLabel);
        ProgressBar modProgress = new ProgressBar(0);
        modProgress.getStyleClass().add("download-progress");
        modProgress.setMaxWidth(Double.MAX_VALUE);
        modProgress.setVisible(false);
        modProgress.managedProperty().bind(modProgress.visibleProperty());
        AtomicLong searchGeneration = new AtomicLong();
        AtomicLong versionGeneration = new AtomicLong();
        AtomicLong downloadGeneration = new AtomicLong();
        AtomicLong activeDownloadGeneration = new AtomicLong();
        AtomicLong descriptionGeneration = new AtomicLong();
        AtomicReference<DownloadTaskCenter.TaskHandle<?>> activeDownloadTask = new AtomicReference<>();
        configureDialogCancellation(dialog, searchGeneration, versionGeneration,
                downloadGeneration, descriptionGeneration, activeDownloadTask,
                activeDownloadGeneration, modProgress);
        Label dialogStatus = new Label("正在加载 Modrinth 列表");
        dialogStatus.getStyleClass().add("status-detail");
        dialogStatus.setWrapText(true);
        Button importBtn = new Button("modpack".equals(target.projectType) ? "安装并启动" : "导入");
        importBtn.getStyleClass().addAll("app-button", "primary-button");
        importBtn.setDisable(true);
        Button folderBtn = new Button("打开实例目录");
        folderBtn.getStyleClass().addAll("app-button", "secondary-button");
        folderBtn.setOnAction(e -> {
            ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
            File importDir = target.folderResolver.apply(inst.profileId());
            try {
                ui.ensureDirectory(importDir);
                ui.openLocalFolder(importDir, target.title + "目录");
            } catch (IOException error) {
                dialogStatus.setText("无法创建目录: " + ui.cleanMessage(error));
            }
        });
        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("app-button", "ghost-button");
        closeBtn.setOnAction(e -> dialog.close());
        resultList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            long selectedDescriptionGeneration = descriptionGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            importBtn.setDisable(true);
            projectVersionCombo.getItems().clear();
            projectVersionCombo.setDisable(selected == null);
            descriptionLabel.setText(selected == null ? "选择一个结果查看简介" : "正在翻译中文简介…");
            if (selected != null) {
                searchController.setTranslatedProjectDescription(selected, descriptionLabel,
                        descriptionGeneration, selectedDescriptionGeneration);
                ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
                searchController.loadProjectVersions(sourceCombo.getValue(), target, selected,
                        inst, projectVersionCombo, dialogStatus, importBtn, versionGeneration);
            }
        });
        projectVersionCombo.valueProperty().addListener((obs, oldValue, selectedVersion) ->
                importBtn.setDisable(selectedVersion == null
                        || resultList.getSelectionModel().getSelectedItem() == null));
        searchBtn.setOnAction(e -> searchController.searchModrinthContent(sourceCombo.getValue(), target,
                downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue()),
                searchField, resultList, dialogStatus, searchBtn, importBtn, searchGeneration));
        searchField.setOnAction(e -> searchBtn.fire());
        sourceCombo.setOnAction(e -> {
            versionGeneration.incrementAndGet();
            projectVersionCombo.getItems().clear();
            projectVersionCombo.setDisable(true);
            resultList.getItems().clear();
            importBtn.setDisable(true);
            searchBtn.fire();
        });
        targetProfileCombo.setOnAction(e -> {
            ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
            if (target.usesLoader()) {
                loaderCombo.getItems().setAll(inst.loader());
                loaderCombo.setValue(inst.loader());
            }
            downloadWorkflow.updateContentTargetLabel(target, inst, targetLabel);
            versionGeneration.incrementAndGet();
            projectVersionCombo.getItems().clear();
            projectVersionCombo.setDisable(true);
            resultList.getItems().clear();
            importBtn.setDisable(true);
            descriptionLabel.setText("正在加载所选实例的兼容内容...");
            searchController.searchModrinthContent(sourceCombo.getValue(), target, inst,
                    searchField, resultList, dialogStatus, searchBtn, importBtn, searchGeneration);
        });
        configureImportAction(importBtn, targetProfileCombo, sourceCombo, target, resultList,
                projectVersionCombo, dialogStatus, modProgress, searchBtn, downloadGeneration,
                activeDownloadGeneration, activeDownloadTask);
        HBox actions = new HBox(10, importBtn, folderBtn, closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox dialogRoot = new VBox(14,
                ui.createSurface("下载 " + target.title,
                        "先选择目标实例；加载器由实例锁定，下载完成后只导入该实例",
                        targetBar, searchBar, targetLabel),
                ui.createSurface("结果与简介",
                        "选择项目后还需要选择一个与目标实例兼容的具体版本",
                        resultList, descriptionLabel, projectVersionCombo),
                ui.createSurface("导入进度", null, dialogStatus, modProgress, actions));
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(18));
        Scene scene = new Scene(ui.createWheelScrollPane(dialogRoot), 780, 650);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        ui.applyThemeToScene(scene, ui.settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
        searchController.searchModrinthContent(sourceCombo.getValue(), target, initialInstance,
                searchField, resultList, dialogStatus, searchBtn, importBtn, searchGeneration);
    }

    private void configureDialogCancellation(
            Stage dialog, AtomicLong searchGeneration, AtomicLong versionGeneration,
            AtomicLong downloadGeneration, AtomicLong descriptionGeneration,
            AtomicReference<DownloadTaskCenter.TaskHandle<?>> activeDownloadTask,
            AtomicLong activeDownloadGeneration, ProgressBar modProgress) {
        dialog.setOnHidden(e -> {
            searchGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            downloadGeneration.incrementAndGet();
            descriptionGeneration.incrementAndGet();
            DownloadTaskCenter.TaskHandle<?> task = activeDownloadTask.getAndSet(null);
            if (task != null) task.cancel();
            if (activeDownloadGeneration.getAndSet(0) != 0) {
                ui.stopProgressAnimation(modProgress, true);
                ui.stopProgressAnimation(ui.downloadProgress, true);
                ui.setControlsBusy(false);
            }
        });
    }

    private void configureImportAction(
            Button importBtn, ComboBox<String> targetProfileCombo, ComboBox<ContentSource> sourceCombo,
            ContentTarget target, ListView<ContentProject> resultList,
            ComboBox<ContentVersion> projectVersionCombo, Label dialogStatus, ProgressBar modProgress,
            Button searchBtn, AtomicLong downloadGeneration, AtomicLong activeDownloadGeneration,
            AtomicReference<DownloadTaskCenter.TaskHandle<?>> activeDownloadTask) {
        importBtn.setOnAction(e -> {
            ContentInstance inst = downloadWorkflow.resolveContentInstance(targetProfileCombo.getValue());
            File importDir = target.folderResolver.apply(inst.profileId());
            try {
                ui.ensureDirectory(importDir);
            } catch (IOException error) {
                dialogStatus.setText("无法创建目录: " + ui.cleanMessage(error));
                return;
            }
            DownloadTaskCenter.TaskHandle<?> task = downloadWorkflow.downloadSelectedContent(
                    sourceCombo.getValue(), target, resultList.getSelectionModel().getSelectedItem(),
                    projectVersionCombo.getValue(), inst, importDir, dialogStatus, modProgress,
                    searchBtn, importBtn, targetProfileCombo, downloadGeneration,
                    activeDownloadGeneration);
            activeDownloadTask.set(task);
        });
    }
}
