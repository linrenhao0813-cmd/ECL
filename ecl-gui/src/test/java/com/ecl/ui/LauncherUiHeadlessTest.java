package com.ecl.ui;

import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LauncherUiHeadlessTest extends ApplicationTest {
    private LauncherUI launcher;
    private Stage stage;

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = primaryStage;
        launcher.start(primaryStage);
    }

    @Override
    public void stop() {
        if (launcher != null) {
            launcher.stop();
        }
    }

    @Test
    void rendersEveryPrimaryNavigationView() {
        for (AppView view : AppView.values()) {
            interact(() -> launcher.setActiveView(view));
            Scene scene = stage.getScene();
            assertNotNull(scene, () -> view + " did not retain the launcher scene");
            assertFalse(scene.getRoot().lookupAll(".main-body").isEmpty(),
                    () -> view + " did not render the workspace");
        }
    }
}
