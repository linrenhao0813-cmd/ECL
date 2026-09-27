package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

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
        navigation.setMinWidth(Region.USE_PREF_SIZE);
        buttons.clear();
        for (AppView view : AppView.values()) {
            navigation.getChildren().add(createButton(view));
        }
        showSelected(selected);
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
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setGraphic(ForestIcons.create(switch (view) {
            case HOME -> "home";
            case VERSIONS -> "instances";
            case SAVES -> "saves";
            case DOWNLOADS -> "download";
            case SERVERS -> "servers";
            case SETTINGS -> "settings";
        }));
        button.setGraphicTextGap(9);
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> selectionHandler.accept(view));
        buttons.put(view, button);
        return button;
    }

    private static String titleFor(AppView view) {
        return switch (view) {
            case HOME -> Messages.get("nav.short.home");
            case VERSIONS -> GuiMessages.get("forest.instances");
            case SAVES -> Messages.get("nav.short.saves");
            case DOWNLOADS -> Messages.get("nav.short.downloads");
            case SERVERS -> Messages.get("nav.short.servers");
            case SETTINGS -> Messages.get("nav.short.settings");
        };
    }

}
