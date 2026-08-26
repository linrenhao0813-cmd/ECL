package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Builds and updates the launcher navigation rail independently from page routing. */
final class LauncherNavigationRail {
    private final Map<AppView, Button> buttons = new EnumMap<>(AppView.class);
    private final Consumer<AppView> selectionHandler;

    LauncherNavigationRail(Consumer<AppView> selectionHandler) {
        this.selectionHandler = selectionHandler;
    }

    HBox createTopNavigation(AppView selected) {
        HBox navigation = new HBox(4);
        navigation.getStyleClass().add("global-nav");
        navigation.setAlignment(Pos.CENTER);
        buttons.clear();
        for (AppView view : AppView.values()) {
            navigation.getChildren().add(createButton(view));
        }
        Platform.runLater(() -> showSelected(selected));
        return navigation;
    }

    void showSelected(AppView selected) {
        buttons.forEach((view, button) -> {
            button.getStyleClass().remove("nav-button-selected");
            if (view == selected) {
                button.getStyleClass().add("nav-button-selected");
            }
        });
    }

    void refreshTexts() {
        buttons.forEach((view, button) -> button.setText(titleFor(view)));
    }

    private Button createButton(AppView view) {
        Button button = new Button(titleFor(view));
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> selectionHandler.accept(view));
        buttons.put(view, button);
        return button;
    }

    private static String titleFor(AppView view) {
        return switch (view) {
            case HOME -> Messages.get("nav.short.home");
            case SAVES -> Messages.get("nav.short.saves");
            case VERSIONS -> Messages.get("nav.short.versions");
            case DOWNLOADS -> Messages.get("nav.short.downloads");
            case MODRINTH -> Messages.get("nav.short.modrinth");
            case SERVERS -> Messages.get("nav.short.servers");
            case LOGS -> Messages.get("nav.short.logs");
            case SETTINGS -> Messages.get("nav.short.settings");
        };
    }

    private static VBox createTelemetryFooter() {
        VBox box = new VBox(8);
        box.getStyleClass().add("nav-rail-footer");
        box.getChildren().addAll(telemetryRow("telemetry.cpu", 0.12, "12%"),
                telemetryRow("telemetry.memory", 0.42, "4.2G"));
        return box;
    }

    private static HBox telemetryRow(String labelKey, double progress, String value) {
        Label label = new Label(Messages.get(labelKey));
        label.getStyleClass().add("telemetry-label");
        ProgressBar bar = new ProgressBar(progress);
        bar.setMaxWidth(Double.MAX_VALUE);
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("telemetry-value");
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox row = new HBox(8, label, bar, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
