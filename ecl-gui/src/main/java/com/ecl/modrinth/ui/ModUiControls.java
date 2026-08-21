package com.ecl.modrinth.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;

/** Creates consistently styled controls used by mod browser components. */
final class ModUiControls {
    private ModUiControls() {
    }

    static Button button(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", styleClass, "compact-button");
        return button;
    }

    static Label versionBadge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("mod-version-badge", styleClass);
        return label;
    }
}
