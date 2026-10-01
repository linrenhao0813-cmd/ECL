package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Applies launcher locale labels and the fixed dark theme class. */
final class LauncherThemeManager {
    private LauncherThemeManager() {
    }

    static String languageDisplayName(String tag) {
        return switch (tag == null ? "" : tag) {
            case "zh-TW" -> Messages.get("language.zhTW");
            case "en" -> Messages.get("language.en");
            default -> Messages.get("language.zhCN");
        };
    }

    static void applyToAllWindows(Stage primaryStage) {
        if (primaryStage != null && primaryStage.getScene() != null) {
            applyToScene(primaryStage.getScene());
        }
        for (Window window : Window.getWindows()) {
            if (window != primaryStage && window.getScene() != null) {
                applyToScene(window.getScene());
            }
        }
    }

    static void applyToScene(Scene scene) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        Node root = scene.getRoot();
        root.getStyleClass().remove("theme-light");
        if (!root.getStyleClass().contains("scene-root")) {
            root.getStyleClass().add("scene-root");
        }
        if (!root.getStyleClass().contains("theme-dark")) {
            root.getStyleClass().add("theme-dark");
        }
    }
}
