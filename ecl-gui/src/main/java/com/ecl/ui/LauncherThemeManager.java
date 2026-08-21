package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Applies launcher locale labels and light/dark theme classes. */
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

    static String themeDisplayName(String theme) {
        return "LIGHT".equalsIgnoreCase(theme)
                ? Messages.get("theme.light") : Messages.get("theme.dark");
    }

    static String normalize(String theme) {
        return "LIGHT".equalsIgnoreCase(theme) ? "LIGHT" : "DARK";
    }

    static void applyToAllWindows(Stage primaryStage, String requestedTheme) {
        if (primaryStage != null && primaryStage.getScene() != null) {
            applyToScene(primaryStage.getScene(), requestedTheme);
        }
        for (Window window : Window.getWindows()) {
            if (window != primaryStage && window.getScene() != null) {
                applyToScene(window.getScene(), requestedTheme);
            }
        }
    }

    static void applyToScene(Scene scene, String requestedTheme) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        Node root = scene.getRoot();
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add("LIGHT".equals(normalize(requestedTheme))
                ? "theme-light" : "theme-dark");
    }
}
