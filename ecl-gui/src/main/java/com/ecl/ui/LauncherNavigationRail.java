package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Builds and updates the launcher navigation rail independently from page routing. */
final class LauncherNavigationRail {
    private final Map<AppView, Button> buttons = new EnumMap<>(AppView.class);
    private final Consumer<AppView> selectionHandler;
    private VBox rail;
    private boolean compact;

    LauncherNavigationRail(Consumer<AppView> selectionHandler) {
        this.selectionHandler = selectionHandler;
    }

    /** Builds the persistent left navigation column used by the window shell. */
    VBox createVerticalNavigation(AppView selected) {
        rail = new VBox(2);
        rail.getStyleClass().add("nav-rail-vertical");
        rail.setAlignment(Pos.TOP_LEFT);
        rail.setMinWidth(Region.USE_PREF_SIZE);
        buttons.clear();
        for (AppView view : AppView.values()) {
            rail.getChildren().add(createButton(view));
        }
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        rail.getChildren().add(spacer);
        showSelected(selected);
        applyCompact();
        return rail;
    }

    /** Collapses the rail to an icon-only column for narrow windows. */
    void setCompact(boolean compact) {
        if (compact == this.compact) {
            return;
        }
        this.compact = compact;
        applyCompact();
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
        if (compact) {
            buttons.forEach((view, button) ->
                    button.setTooltip(new Tooltip(titleFor(view))));
            return;
        }
        buttons.forEach((view, button) -> button.setText(titleFor(view)));
    }

    private void applyCompact() {
        if (rail == null) {
            return;
        }
        if (compact) {
            if (!rail.getStyleClass().contains("nav-rail-compact")) {
                rail.getStyleClass().add("nav-rail-compact");
            }
        } else {
            rail.getStyleClass().remove("nav-rail-compact");
        }
        buttons.forEach((view, button) -> {
            String title = titleFor(view);
            button.setText(compact ? null : title);
            button.setTooltip(compact ? new Tooltip(title) : null);
        });
    }

    private Button createButton(AppView view) {
        Button button = new Button(titleFor(view));
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setAccessibleText(titleFor(view));
        button.setGraphic(ForestIcons.create(switch (view) {
            case HOME -> "home";
            case VERSIONS -> "instances";
            case SAVES -> "saves";
            case DOWNLOADS -> "download";
            case SERVERS -> "servers";
            case SETTINGS -> "settings";
        }));
        button.setGraphicTextGap(10);
        button.getStyleClass().addAll("nav-button", "nav-button-vertical");
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
