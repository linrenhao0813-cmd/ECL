package com.ecl.ui;

import com.ecl.modrinth.pack.ModpackUpdate;
import com.ecl.modrinth.pack.ModpackUpdateService;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Builds the unified download workspace and its content-library sections. */
final class ContentLibraryPageFactory {
    private final LauncherUI ui;

    ContentLibraryPageFactory(LauncherUI ui) {
        this.ui = ui;
    }

    VBox createPage(DownloadSection initialSection) {
        VBox page = ui.createMainPage();
        Label pageTitle = new Label(Messages.get("download.hub.title"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("download.hub.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("content-library-heading");

        VBox navigation = new VBox(8);
        navigation.getStyleClass().add("content-library-nav");
        navigation.setPrefWidth(210);
        navigation.setMinWidth(210);
        navigation.setMaxWidth(210);
        Label navigationTitle = new Label(Messages.get("download.hub.categories"));
        navigationTitle.getStyleClass().add("content-library-nav-title");
        Label navigationHint = new Label(Messages.get("download.hub.hint"));
        navigationHint.getStyleClass().add("content-library-nav-hint");
        navigation.getChildren().addAll(navigationTitle, navigationHint);

        StackPane content = new StackPane();
        content.getStyleClass().add("content-library-content");
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);

        List<Button> categoryButtons = new java.util.ArrayList<>();
        Button instancesButton = createDownloadNavButton("I",
                Messages.get("download.instances.title"),
                Messages.get("download.instances.detail"));
        categoryButtons.add(instancesButton);
        navigation.getChildren().add(instancesButton);
        instancesButton.setOnAction(event -> {
            selectCategory(categoryButtons, instancesButton);
            ui.downloadSection = DownloadSection.INSTANCES;
            ui.closeActiveModBrowserView();
            content.getChildren().setAll(embedded(ui.pageFactory.createVersionsPage()));
        });

        Button firstContentButton = null;
        for (ContentTarget target : ui.contentTargets) {
            Button categoryButton = createContentLibraryNavButton(target);
            if (firstContentButton == null) {
                firstContentButton = categoryButton;
            }
            categoryButtons.add(categoryButton);
            navigation.getChildren().add(categoryButton);
            categoryButton.setOnAction(event -> {
                selectCategory(categoryButtons, categoryButton);
                ui.downloadSection = DownloadSection.CONTENT;
                ui.closeActiveModBrowserView();
                Node selectedContent = switch (target.projectType) {
                    case "mod" -> ui.createModLibraryContent();
                    case "server" -> ui.createServerJarLibraryContent();
                    default -> ui.createContentLibraryBrowser(target);
                };
                content.getChildren().setAll(selectedContent);
            });
        }
        Button packUpdatesButton = createPackUpdatesNavButton();
        categoryButtons.add(packUpdatesButton);
        navigation.getChildren().add(packUpdatesButton);
        packUpdatesButton.setOnAction(event -> {
            selectCategory(categoryButtons, packUpdatesButton);
            ui.downloadSection = DownloadSection.CONTENT;
            ui.closeActiveModBrowserView();
            content.getChildren().setAll(createPackUpdatesContent());
        });

        Button tasksButton = createDownloadNavButton("T",
                Messages.get("download.tasks.title"),
                Messages.get("download.tasks.detail"));
        categoryButtons.add(tasksButton);
        navigation.getChildren().add(tasksButton);
        tasksButton.setOnAction(event -> {
            selectCategory(categoryButtons, tasksButton);
            ui.downloadSection = DownloadSection.TASKS;
            ui.closeActiveModBrowserView();
            ui.downloadTasksPage = ui.pageFactory.createDownloadTasksPage();
            content.getChildren().setAll(embedded(ui.downloadTasksPage));
        });

        HBox library = new HBox(18, navigation, content);
        library.getStyleClass().add("content-library-layout");
        library.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);
        page.getChildren().addAll(pageHeading, library);
        if (initialSection == DownloadSection.TASKS) {
            tasksButton.fire();
        } else if (initialSection == DownloadSection.CONTENT && firstContentButton != null) {
            firstContentButton.fire();
        } else {
            instancesButton.fire();
        }
        return page;
    }

    private static void selectCategory(List<Button> buttons, Button selected) {
        buttons.forEach(button -> button.getStyleClass().remove("content-library-nav-item-active"));
        selected.getStyleClass().add("content-library-nav-item-active");
    }

    private static Node embedded(Region content) {
        content.setMinWidth(0);
        content.setPrefWidth(Region.USE_COMPUTED_SIZE);
        content.setMaxWidth(Double.MAX_VALUE);
        return content;
    }

    private Button createContentLibraryNavButton(ContentTarget target) {
        String detail = switch (target.projectType) {
            case "mod" -> Messages.get("content.detail.mods");
            case "shader" -> Messages.get("content.detail.shaders");
            case "resourcepack" -> Messages.get("content.detail.resourcepacks");
            case "modpack" -> Messages.get("content.detail.modpacks");
            case "server" -> Messages.get("content.detail.server");
            default -> target.subtitle;
        };
        return createDownloadNavButton(target.initial, target.title, detail);
    }

    private Button createDownloadNavButton(String initial, String titleText, String detailText) {
        Label icon = new Label(initial);
        icon.getStyleClass().add("content-library-nav-icon");
        Label title = new Label(titleText);
        title.getStyleClass().add("content-library-nav-item-title");
        Label detail = new Label(detailText);
        detail.getStyleClass().add("content-library-nav-item-detail");
        VBox labels = new VBox(2, title, detail);
        HBox row = new HBox(10, icon, labels);
        row.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button();
        button.setGraphic(row);
        button.getStyleClass().add("content-library-nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private Button createPackUpdatesNavButton() {
        return createDownloadNavButton("↻", Messages.get("download.packUpdates.title"),
                Messages.get("download.packUpdates.detail"));
    }

    private Node createPackUpdatesContent() {
        VBox page = new VBox(14);
        page.getStyleClass().add("content-library-content");
        Label title = new Label("整合包更新");
        title.getStyleClass().add("content-library-section-title");
        Label hint = new Label("只检查已通过 Modrinth 安装并记录来源的整合包，更新会保留存档等实例文件。");
        hint.getStyleClass().add("status-detail");
        hint.setWrapText(true);

        ListView<ModpackUpdate> list = new ListView<>();
        list.getStyleClass().add("mod-result-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(new Label("尚未发现可更新的整合包。"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ModpackUpdate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(item.instance().name());
                name.getStyleClass().add("mod-item-title");
                Label detail = new Label(item.instance().currentVersion() + "  →  "
                        + item.availableVersion().versionNumber() + "   ·   "
                        + item.instance().minecraftVersion()
                        + (item.instance().loader().isBlank() ? "" : " / " + item.instance().loader()));
                detail.getStyleClass().add("status-detail");
                detail.setWrapText(true);
                setGraphic(new VBox(3, name, detail));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Label status = ui.createBodyText("点击“检查更新”扫描已安装整合包。");
        Button[] controls = new Button[3];
        controls[0] = ui.createActionButton("检查更新", "secondary-button",
                () -> checkPackUpdates(list, status, controls[0], controls[1], controls[2]));
        controls[1] = ui.createActionButton("更新选中", "primary-button",
                () -> applyPackUpdates(list.getSelectionModel().getSelectedItems(), list, status,
                        controls[0], controls[1], controls[2]));
        controls[2] = ui.createActionButton("一键更新全部", "primary-button",
                () -> applyPackUpdates(List.copyOf(list.getItems()), list, status,
                        controls[0], controls[1], controls[2]));
        Button check = controls[0];
        Button updateSelected = controls[1];
        Button updateAll = controls[2];
        updateSelected.setDisable(true);
        updateAll.setDisable(true);
        list.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ModpackUpdate>) change ->
                        updateSelected.setDisable(list.getSelectionModel().getSelectedItems().isEmpty()));
        HBox actions = new HBox(8, check, updateSelected, updateAll);
        actions.setAlignment(Pos.CENTER_RIGHT);
        page.getChildren().addAll(ui.createSurface("整合包更新检测", null,
                title, hint, list, status, actions));
        Platform.runLater(() -> checkPackUpdates(list, status, check, updateSelected, updateAll));
        return page;
    }

    private void checkPackUpdates(ListView<ModpackUpdate> list, Label status,
                                  Button check, Button updateSelected, Button updateAll) {
        setPackUpdateControls(true, check, updateSelected, updateAll);
        status.setText("正在检查整合包更新...");
        ModpackUpdateService service = ui.controller.modpackUpdateService();
        service.checkUpdates(ui.getConfiguredGameRootDir().toPath(),
                        ui.controller.preferredModReleaseChannel())
                .whenComplete((updates, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        list.getItems().clear();
                        status.setText("检查失败: " + ui.cleanMessage(error));
                    } else {
                        list.getItems().setAll(updates);
                        status.setText(updates.isEmpty()
                                ? "所有已记录来源的整合包均为最新版本。"
                                : "发现 " + updates.size() + " 个整合包可更新。");
                    }
                    setPackUpdateControls(false, check, updateSelected, updateAll);
                    updateAll.setDisable(list.getItems().isEmpty());
                    updateSelected.setDisable(list.getSelectionModel().getSelectedItems().isEmpty());
                }));
    }

    private void applyPackUpdates(List<ModpackUpdate> updates, ListView<ModpackUpdate> list,
                                  Label status, Button check, Button updateSelected, Button updateAll) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        setPackUpdateControls(true, check, updateSelected, updateAll);
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (ModpackUpdate update : updates) {
            chain = chain.thenCompose(count -> ui.controller.modpackUpdateService()
                    .applyUpdate(update, ui.getConfiguredGameRootDir().toPath(),
                            new MrpackInstaller.Listener() {
                                @Override
                                public void onStatus(String message) {
                                    Platform.runLater(() -> status.setText(message));
                                }

                                @Override
                                public void onProgress(long downloaded, long total) {
                                    if (total > 0) {
                                        Platform.runLater(() -> status.setText("正在更新 "
                                                + update.instance().name() + " · "
                                                + formatPackBytes(downloaded) + " / "
                                                + formatPackBytes(total)));
                                    }
                                }
                            }).thenApply(result -> count + 1));
        }
        chain.whenComplete((count, error) -> Platform.runLater(() -> {
            setPackUpdateControls(false, check, updateSelected, updateAll);
            if (error != null) {
                status.setText("批量更新中断: " + ui.cleanMessage(error));
            } else {
                status.setText("已完成 " + count + " 个整合包更新。");
                list.getItems().removeAll(updates);
                updateAll.setDisable(list.getItems().isEmpty());
            }
            updateSelected.setDisable(list.getSelectionModel().getSelectedItems().isEmpty());
        }));
    }

    private void setPackUpdateControls(boolean busy, Button check,
                                       Button updateSelected, Button updateAll) {
        check.setDisable(busy);
        updateSelected.setDisable(busy);
        updateAll.setDisable(busy);
    }

    private static String formatPackBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
