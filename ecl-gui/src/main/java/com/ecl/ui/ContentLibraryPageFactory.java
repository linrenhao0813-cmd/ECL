package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

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

        HBox categoryBar = new HBox(8);
        categoryBar.getStyleClass().add("content-library-category-bar");
        categoryBar.setAlignment(Pos.CENTER_LEFT);

        Label targetLabel = new Label();
        targetLabel.setId("content-install-target");
        targetLabel.getStyleClass().add("content-target-value");
        ui.contentTargetLabel = targetLabel;
        HBox targetStrip = new HBox(8);
        targetStrip.getStyleClass().add("content-target-strip");
        targetStrip.setAlignment(Pos.CENTER_LEFT);
        Label targetCaption = new Label(Messages.get("content.target.caption"));
        targetCaption.getStyleClass().add("content-target-caption");
        Region targetSpacer = new Region();
        HBox.setHgrow(targetSpacer, Priority.ALWAYS);
        Button manageTarget = ui.createLinkButton(
                Messages.get("instanceBar.manage"), () -> ui.setActiveView(AppView.VERSIONS));
        targetStrip.getChildren().addAll(targetCaption, targetLabel, targetSpacer, manageTarget);

        StackPane content = new StackPane();
        content.getStyleClass().add("content-library-content");
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        List<Button> categoryButtons = new java.util.ArrayList<>();
        Map<String, Button> buttonsByKey = new java.util.LinkedHashMap<>();
        Button instancesButton = createCategoryChip("I",
                Messages.get("download.instances.title"),
                Messages.get("download.instances.detail"));
        categoryButtons.add(instancesButton);
        buttonsByKey.put("", instancesButton);
        categoryBar.getChildren().add(instancesButton);
        instancesButton.setOnAction(event -> {
            selectCategory(categoryButtons, instancesButton);
            ui.downloadSection = DownloadSection.INSTANCES;
            ui.contentCategoryKey = "";
            ui.contentTargetMode = "instances";
            ui.closeActiveModBrowserView();
            content.getChildren().setAll(embedded(ui.pageFactory.createVersionsPage()));
            ui.updateRuntimeSummary();
        });

        Button firstContentButton = null;
        for (ContentTarget target : ui.contentTargets) {
            Button categoryButton = createContentChip(target);
            if (firstContentButton == null) {
                firstContentButton = categoryButton;
            }
            categoryButtons.add(categoryButton);
            buttonsByKey.put(target.projectType, categoryButton);
            categoryBar.getChildren().add(categoryButton);
            categoryButton.setOnAction(event -> {
                selectCategory(categoryButtons, categoryButton);
                ui.downloadSection = DownloadSection.CONTENT;
                ui.contentCategoryKey = target.projectType;
                // Server jars go to their own directory, so the banner must not reuse instance wording.
                ui.contentTargetMode = "server".equals(target.projectType) ? "server" : "instance";
                ui.closeActiveModBrowserView();
                Node selectedContent = switch (target.projectType) {
                    case "mod" -> ui.createModLibraryContent();
                    case "server" -> ui.createServerJarLibraryContent();
                    default -> ui.createContentLibraryBrowser(target);
                };
                content.getChildren().setAll(selectedContent);
                ui.updateRuntimeSummary();
            });
        }
        page.getChildren().addAll(pageHeading, targetStrip, categoryBar, content);
        // Returning to the content page restores the category the user left on.
        Button remembered = ui.contentCategoryKey == null ? null : buttonsByKey.get(ui.contentCategoryKey);
        if (remembered != null) {
            remembered.fire();
        } else if (initialSection == DownloadSection.CONTENT && firstContentButton != null) {
            firstContentButton.fire();
        } else {
            instancesButton.fire();
        }
        ui.updateRuntimeSummary();
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

    private Button createContentChip(ContentTarget target) {
        String detail = switch (target.projectType) {
            case "mod" -> Messages.get("content.detail.mods");
            case "shader" -> Messages.get("content.detail.shaders");
            case "resourcepack" -> Messages.get("content.detail.resourcepacks");
            case "modpack" -> Messages.get("content.detail.modpacks");
            case "server" -> Messages.get("content.detail.server");
            default -> target.subtitle;
        };
        return createCategoryChip(target.initial, target.title, detail);
    }

    /** Horizontal category chip. The detail text lives in the tooltip to keep the bar compact. */
    private Button createCategoryChip(String initial, String titleText, String detailText) {
        Label icon = new Label(initial);
        icon.getStyleClass().add("content-library-nav-icon");
        Button button = new Button(titleText);
        button.setGraphic(icon);
        button.setGraphicTextGap(8);
        button.setTooltip(new javafx.scene.control.Tooltip(detailText));
        button.getStyleClass().addAll("content-library-nav-item", "content-category-chip");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

}
