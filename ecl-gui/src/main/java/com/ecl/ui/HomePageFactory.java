package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
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
        pane.setMaxHeight(Double.MAX_VALUE);
        pane.setMinWidth(0);
        HBox.setHgrow(pane, Priority.ALWAYS);
        // Shared controls continue to own authentication and the selected local instance.
        ui.createForm();
        StackPane hero = createLaunchHero();
        VBox.setVgrow(hero, Priority.ALWAYS);
        pane.getChildren().add(hero);
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
                ui.selectedRuntimeMetaLabel, ui.createActionBar(),
                ui.instanceUpdates.createStatusPane(), description);
        details.getStyleClass().add("forest-details");
        details.setAlignment(Pos.CENTER_LEFT);
        details.setMaxWidth(Double.MAX_VALUE);
        StackPane hero = new StackPane(landscape, shade, details);
        hero.setId("forest-hero");
        hero.getStyleClass().add("forest-hero");
        hero.setMaxHeight(Double.MAX_VALUE);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        hero.setClip(clip);
        return hero;
    }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
