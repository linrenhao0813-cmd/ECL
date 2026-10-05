package com.ecl.ui;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Small native vector icons that inherit the active theme. */
final class ForestIcons {
    private ForestIcons() { }

    static StackPane create(String name) {
        SVGPath path = new SVGPath();
        path.setContent(switch (name) {
            case "home" -> "M3 10 L12 3 L21 10 M5 9 V21 H10 V15 H14 V21 H19 V9";
            case "instances" -> "M12 2 L22 7 V17 L12 22 L2 17 V7 Z M2 7 L12 12 L22 7 M12 12 V22";
            case "download" -> "M12 2 V16 M6 10 L12 16 L18 10 M3 18 V22 H21 V18";
            case "servers" -> "M3 3 H21 V10 H3 Z M3 14 H21 V21 H3 Z M6 6 H8 M6 17 H8";
            case "saves" -> "M2 6 H9 L11 9 H22 V21 H2 Z";
            case "settings" -> "M4 5 H20 M4 12 H20 M4 19 H20 M8 2 V8 M16 9 V15 M10 16 V22";
            case "memory" -> "M3 6 H21 V18 H3 Z M7 9 V15 M12 9 V15 M17 9 V15 M7 18 V22 M12 18 V22 M17 18 V22";
            case "check" -> "M21 12 A9 9 0 1 1 12 3 A9 9 0 0 1 21 12 M7 12 L11 16 L17 8";
            case "clock" -> "M21 12 A9 9 0 1 1 12 3 A9 9 0 0 1 21 12 M12 6 V12 L16 15";
            case "account" -> "M16 6 A4 4 0 1 1 8 6 A4 4 0 0 1 16 6 M3 22 V19 Q3 13 12 13 Q21 13 21 19 V22";
            case "java" -> "M4 8 H17 V14 Q17 20 10 20 Q4 20 4 14 Z M17 9 H21 V14 H17 M2 23 H20 M9 5 Q13 3 10 0";
            default -> "M3 21 Q3 4 21 3 Q20 21 3 21 M3 21 L16 8";
        });
        path.getStyleClass().add("forest-icon-path");
        StackPane icon = new StackPane(path);
        icon.getStyleClass().add("forest-icon");
        icon.setMinSize(24, 24);
        icon.setPrefSize(24, 24);
        icon.setMaxSize(24, 24);
        icon.setMouseTransparent(true);
        return icon;
    }
}
