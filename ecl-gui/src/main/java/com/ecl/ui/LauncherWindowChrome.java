package com.ecl.ui;

import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/** Creates standard window controls and drag behavior for the undecorated launcher stage. */
final class LauncherWindowChrome {
    private final Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;

    LauncherWindowChrome(Stage stage) {
        this.stage = stage;
    }

    HBox createControls() {
        Button minimize = button("—", () -> stage.setIconified(true));
        Button maximize = button("□", () -> stage.setMaximized(!stage.isMaximized()));
        Button close = button("×", stage::close);
        close.getStyleClass().add("window-close-button");
        HBox controls = new HBox(4, minimize, maximize, close);
        controls.getStyleClass().add("window-controls");
        controls.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        return controls;
    }

    void installDragBehavior(HBox titleBar) {
        titleBar.setOnMousePressed(event -> {
            dragOffsetX = event.getSceneX();
            dragOffsetY = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            if (!stage.isMaximized()) {
                stage.setX(event.getScreenX() - dragOffsetX);
                stage.setY(event.getScreenY() - dragOffsetY);
            }
        });
        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("window-button");
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
    }
}
