package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/** Builds the forest launch home while retaining the shared launch/account controls. */
final class HomePageFactory {
    private final LauncherUI ui;

    HomePageFactory(LauncherUI ui) {
        this.ui = ui;
    }

    VBox getOrCreate() {
        if (ui.homePage == null) {
            ui.homePage = createLaunchPane();
        }
        ui.updateRuntimeSummary();
        return ui.homePage;
    }

    private VBox createLaunchPane() {
        VBox pane = new VBox(18);
        pane.getStyleClass().addAll("launch-pane", "forest-home");
        pane.setPrefWidth(LauncherUI.LAUNCH_WIDTH);
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setMinWidth(0);
        HBox.setHgrow(pane, Priority.ALWAYS);
        // Shared controls continue to own authentication and the selected local instance.
        ui.createForm();
        StackPane hero = createLaunchHero();
        HBox summary = createStatusStrip();
        pane.getChildren().addAll(hero, summary, createActivity());
        return pane;
    }

    private StackPane createLaunchHero() {
        Region landscape = new Region();
        landscape.getStyleClass().add("forest-landscape");
        Region shade = new Region();
        shade.getStyleClass().add("forest-shade");
        Label eyebrow = label(Messages.get("home.currentInstance"), "forest-eyebrow");
        ui.selectedVersionTitleLabel = label(Messages.get("home.selectVersion"), "forest-title");
        ui.selectedVersionTitleLabel.setMaxWidth(600);
        ui.selectedRuntimeMetaLabel = label("", "forest-meta");
        ui.selectedRuntimeMetaLabel.setMaxWidth(600);
        Label description = label(GuiMessages.get("forest.description"), "forest-description");
        description.setWrapText(true);
        description.setMaxWidth(410);
        VBox details = new VBox(16, eyebrow, ui.selectedVersionTitleLabel,
                ui.selectedRuntimeMetaLabel, ui.createActionBar(), description);
        details.getStyleClass().add("forest-details");
        details.setAlignment(Pos.CENTER_LEFT);
        details.setMaxWidth(Double.MAX_VALUE);
        StackPane hero = new StackPane(landscape, shade, details);
        hero.setId("forest-hero");
        hero.getStyleClass().add("forest-hero");
        hero.prefHeightProperty().bind(Bindings.when(ui.primaryStage.heightProperty().lessThan(800))
                .then(360).otherwise(420));
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        hero.setClip(clip);
        return hero;
    }

    private HBox createStatusStrip() {
        ui.homeAccountNameLabel = label(ui.getAuthDisplayName(), "forest-stat-value");
        ui.homeAccountTypeLabel = label("", "forest-stat-caption");
        HBox strip = new HBox(
                stat("account", ui.homeAccountNameLabel, ui.homeAccountTypeLabel));
        strip.getStyleClass().add("forest-status-strip");
        return strip;
    }

    private HBox stat(String icon, Label value, Label caption) {
        value.setMinWidth(0);
        value.setMaxWidth(Double.MAX_VALUE);
        if (value.getTooltip() == null) {
            Tooltip tooltip = new Tooltip();
            tooltip.textProperty().bind(value.textProperty());
            value.setTooltip(tooltip);
        }
        caption.setMinWidth(0);
        VBox text = new VBox(6, value, caption);
        text.setMinWidth(0);
        HBox cell = new HBox(12, ForestIcons.create(icon), text);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.setMinWidth(0);
        cell.setPrefWidth(220);
        cell.setMaxWidth(Double.MAX_VALUE);
        cell.getStyleClass().add("forest-stat");
        HBox.setHgrow(cell, Priority.ALWAYS);
        HBox.setHgrow(text, Priority.ALWAYS);
        return cell;
    }

    private HBox createActivity() {
        ui.statusLabel = label(Messages.get("home.noTasks"), "forest-activity-title");
        ui.detailLabel = label(Messages.get("home.taskDetail"), "forest-stat-caption");
        ui.detailLabel.setMinWidth(0);
        ui.downloadProgress = new ProgressBar(0);
        ui.downloadProgress.setPrefWidth(90);
        ui.downloadProgress.getStyleClass().add("download-progress");
        Button account = ui.createLinkButton(Messages.get("home.manageAccount"), ui::openAccountSettings);
        ui.homeSkinUploadButton = ui.createLinkButton(Messages.get("home.uploadSkin"),
                () -> ui.skins.chooseAndUploadSkin());
        HBox activity = new HBox(12, ui.downloadProgress, ui.statusLabel, ui.detailLabel,
                spacer(), account, ui.homeSkinUploadButton);
        activity.getStyleClass().add("forest-activity");
        activity.setAlignment(Pos.CENTER_LEFT);
        return activity;
    }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    static Node spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
