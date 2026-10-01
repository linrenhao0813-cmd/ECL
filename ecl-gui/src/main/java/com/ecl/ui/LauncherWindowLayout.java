package com.ecl.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Assembles the launcher window shell: title bar, left navigation rail, instance bar, routed page
 * workspace and the shared task/status dock.
 */
final class LauncherWindowLayout {
    /** Below this scene width the rail collapses to icons so page content keeps its room. */
    static final double COMPACT_RAIL_BREAKPOINT = 1280;

    private final LauncherUI ui;

    LauncherWindowLayout(LauncherUI ui) {
        this.ui = ui;
    }

    BorderPane createRoot() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setPadding(Insets.EMPTY);
        root.setTop(createTitleBar());
        root.setLeft(ui.navigationRail.createVerticalNavigation(ui.activeView));
        root.setBottom(ui.statusBar.create());

        ui.workspacePane = new HBox(24);
        ui.workspacePane.getStyleClass().add("main-body");
        ui.workspacePane.setAlignment(Pos.TOP_CENTER);
        ui.workspacePane.setFillHeight(true);

        ui.mainScrollPane = ui.createWheelScrollPane(ui.workspacePane);
        ui.mainScrollPane.setFitToHeight(true);
        VBox.setVgrow(ui.mainScrollPane, Priority.ALWAYS);

        // The instance bar owns the launch-target selector, so it must exist before the home page
        // builds its form.
        ui.instanceBar = ui.instanceBarFactory.create();

        ui.renderActiveView();

        VBox contentShell = new VBox(ui.instanceBar, ui.mainScrollPane);
        contentShell.getStyleClass().add("content-shell");
        contentShell.setMaxWidth(Double.MAX_VALUE);
        contentShell.setMaxHeight(Double.MAX_VALUE);
        root.setCenter(contentShell);
        return root;
    }

    /** Keeps the navigation rail readable when the window narrows or Windows scaling grows. */
    void installResponsiveBehavior(Scene scene) {
        scene.widthProperty().addListener((observable, previous, width) -> {
            ui.navigationRail.setCompact(width.doubleValue() < COMPACT_RAIL_BREAKPOINT);
            ui.applyCompactLayout();
        });
        ui.navigationRail.setCompact(scene.getWidth() < COMPACT_RAIL_BREAKPOINT);
        ui.applyCompactLayout();
    }

    private HBox createTitleBar() {
        HBox titleBar = new HBox(16);
        titleBar.getStyleClass().add("window-title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("ECL");
        title.getStyleClass().addAll("window-title", "brand-label");
        title.setGraphic(ForestIcons.create("leaf"));
        title.setGraphicTextGap(12);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ui.topAuthBadgeLabel = ui.createValueLabel("Steve");
        ui.topAuthBadgeLabel.getStyleClass().add("account-chip");

        Button accountButton = new Button();
        accountButton.setId("top-account-button");
        HBox accountGraphic = new HBox(8, ui.accountAvatarPresenter.view(), ui.topAuthBadgeLabel);
        accountGraphic.setAlignment(Pos.CENTER_LEFT);
        accountButton.setGraphic(accountGraphic);
        accountButton.getStyleClass().add("forest-account-button");
        accountButton.setAccessibleText(GuiMessages.get("accounts.title"));
        accountButton.setOnAction(event -> ui.openAccountSettings());

        LauncherWindowChrome windowChrome = new LauncherWindowChrome(ui.primaryStage);
        HBox windowControls = windowChrome.createControls();

        titleBar.getChildren().addAll(title, spacer, accountButton, windowControls);
        windowChrome.installDragBehavior(titleBar);
        return titleBar;
    }
}
