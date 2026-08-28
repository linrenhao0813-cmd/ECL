package com.ecl.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;

/** Routes the launcher between top-level views and animates content transitions. */
final class LauncherPageRouter {
    private final LauncherUI ui;

    LauncherPageRouter(LauncherUI ui) {
        this.ui = ui;
    }

    void setActiveView(AppView view) {
        if (view == null || ui.workspacePane == null) {
            return;
        }
        if (view == ui.activeView) {
            return;
        }
        int slideDirection = Integer.compare(view.ordinal(), ui.activeView.ordinal());
        if (view != AppView.HOME && ui.passwordField != null) {
            ui.passwordField.clear();
        }
        ui.activeView = view;
        renderActiveView(slideDirection);
        ui.navigationRail.showSelected(view);
        if (ui.authTypeCombo != null && ui.authSummaryLabel != null) {
            ui.updateAuthFields();
        } else {
            ui.updateRuntimeSummary();
        }
    }

    boolean isHomeViewActive() {
        return ui.activeView == AppView.HOME;
    }

    void renderActiveView() {
        renderActiveView(0);
    }

    void renderActiveView(int slideDirection) {
        if (ui.workspacePane == null) {
            return;
        }
        ui.closeActiveModBrowserView();
        ui.closeActiveServerBrowserView();
        ui.workspacePane.getChildren().clear();
        switch (ui.activeView) {
            case HOME -> addMainContent(ui.homePageFactory.getOrCreate(), null);
            case SAVES -> addMainContent(ui.pageFactory.createWorldSavesPage(), null);
            case VERSIONS -> addMainContent(ui.pageFactory.createVersionsPage(), null);
            case DOWNLOADS -> {
                ui.downloadTasksPage = ui.pageFactory.createDownloadTasksPage();
                addMainContent(ui.downloadTasksPage, null);
            }
            case MODRINTH -> addMainContent(ui.contentLibraryPageFactory.createPage(), null);
            case SERVERS -> addMainContent(ui.pageFactory.createServersPage(), null);
            case SETTINGS -> addMainContent(ui.pageFactory.createSettingsPage(), null);
            case LOGS -> addMainContent(ui.pageFactory.createLogsPage(), null);
        }
        if (slideDirection != 0) {
            playContentTransition(slideDirection);
        }
    }

    private void playContentTransition(int direction) {
        if (Boolean.getBoolean("ecl.reduceMotion") || ui.workspacePane.getChildren().isEmpty()) {
            return;
        }
        if (ui.contentTransition != null) {
            ui.contentTransition.stop();
        }
        double offset = 26 * direction;
        Timeline transition = new Timeline();
        for (int i = 0; i < ui.workspacePane.getChildren().size(); i++) {
            Node node = ui.workspacePane.getChildren().get(i);
            double delay = i * 45.0;
            node.setOpacity(0);
            node.setTranslateX(offset);
            transition.getKeyFrames().addAll(
                    new KeyFrame(Duration.millis(delay),
                            new KeyValue(node.opacityProperty(), 0),
                            new KeyValue(node.translateXProperty(), offset)),
                    new KeyFrame(Duration.millis(300 + delay),
                            new KeyValue(node.opacityProperty(), 1, Interpolator.EASE_OUT),
                            new KeyValue(node.translateXProperty(), 0,
                                    Interpolator.SPLINE(0.16, 0.86, 0.24, 1.0)))
            );
        }
        ui.contentTransition = transition;
        ui.contentTransition.play();
    }

    private void addMainContent(Node primary, Node secondary) {
        HBox.setHgrow(primary, Priority.ALWAYS);
        ui.workspacePane.getChildren().add(primary);
        if (secondary != null) {
            HBox.setHgrow(secondary, Priority.NEVER);
            ui.workspacePane.getChildren().add(secondary);
        }
    }
}
