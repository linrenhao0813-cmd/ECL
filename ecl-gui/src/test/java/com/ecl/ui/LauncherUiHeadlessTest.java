package com.ecl.ui;

import com.ecl.ECLConfig;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherUiHeadlessTest extends ApplicationTest {
    private LauncherUI launcher;
    private Stage stage;

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = new Stage();
        launcher.start(stage);
    }

    @Override
    public void stop() {
        if (launcher != null) {
            launcher.stop();
        }
        if (stage != null) stage.close();
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

    @Test
    void localVersionsHaveTheirOwnMenuInsteadOfOpeningDownloads() {
        interact(() -> {
            launcher.setActiveView(AppView.VERSIONS);

            assertEquals(AppView.VERSIONS, launcher.activeView);
            assertNotNull(stage.getScene().lookup("#installed-instance-list"));
        });
    }

    @Test
    void accountSettingsShareLaunchIdentityAcrossPageChanges() {
        interact(() -> {
            String previousName = launcher.usernameField.getText();
            String previousType = launcher.authTypeCombo.getValue();
            try {
                launcher.openAccountSettings();
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                assertEquals(AppView.SETTINGS, launcher.activeView);
                launcher.authTypeCombo.setValue(LauncherUI.AUTH_YGGDRASIL);
                assertTrue(launcher.yggdrasilServerField.isVisible());
                assertTrue(launcher.passwordField.isVisible());
                launcher.authTypeCombo.setValue(LauncherUI.AUTH_MICROSOFT);
                assertTrue(launcher.microsoftAccountCombo.isVisible());
                assertFalse(launcher.usernameField.isVisible());
                launcher.authTypeCombo.setValue(LauncherUI.AUTH_OFFLINE);
                launcher.usernameField.setText("ExplorerTestPlayer");
                ((Button) stage.getScene().lookup("#account-apply")).fire();
                assertEquals("ExplorerTestPlayer", launcher.settingsManager.get(ECLConfig.KEY_USERNAME));
                launcher.setActiveView(AppView.HOME);
                assertEquals("ExplorerTestPlayer", launcher.homeAccountNameLabel.getText());
                launcher.openAccountSettings();
                assertEquals("ExplorerTestPlayer", launcher.usernameField.getText());
                assertTrue(launcher.usernameField.isVisible());
                assertFalse(launcher.passwordField.isVisible());
            } finally {
                launcher.authTypeCombo.setValue(previousType);
                launcher.usernameField.setText(previousName);
                launcher.settingsManager.set(ECLConfig.KEY_AUTH_TYPE, previousType);
                launcher.settingsManager.set(ECLConfig.KEY_USERNAME, previousName);
                launcher.settingsManager.save();
            }
        });
    }
}
