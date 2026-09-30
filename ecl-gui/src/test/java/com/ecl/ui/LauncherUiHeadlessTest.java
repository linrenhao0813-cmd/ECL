package com.ecl.ui;

import com.ecl.ECLConfig;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
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

    @Test
    void upgradeButtonTracksModdedVanillaAndMissingInstances() throws Exception {
        String id = "upgrade-button-test";
        java.nio.file.Path profile = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        java.nio.file.Files.createDirectories(profile.getParent());
        java.nio.file.Files.writeString(profile, """
                {"id":"upgrade-button-test","eclMinecraftVersion":"1.21.1","eclModLoader":"fabric",
                 "eclModLoaderVersion":"0.16.0","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient"}
                """);
        try {
            interact(() -> {
                launcher.versionManager.invalidateLocalVersionProfiles();
                launcher.versionCombo.getItems().add(id);
                launcher.versionCombo.setValue(id);
                assertFalse(launcher.updateInstanceButton.isDisabled());
                launcher.setControlsBusy(true);
                assertTrue(launcher.updateInstanceButton.isDisabled());
                launcher.setControlsBusy(false);
                assertFalse(launcher.updateInstanceButton.isDisabled());
                launcher.versionCombo.setValue("uninstalled-vanilla-test");
                assertTrue(launcher.updateInstanceButton.isDisabled());
                launcher.versionCombo.setValue(null);
                assertTrue(launcher.updateInstanceButton.isDisabled());
            });
        } finally {
            java.nio.file.Files.deleteIfExists(profile);
            launcher.versionManager.invalidateLocalVersionProfiles();
        }
    }

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
    void forestHomeRoutesRealControlsAndReflectsBusyState() {
        interact(() -> {
            assertNotNull(stage.getScene().lookup("#forest-hero"));
            StackPane avatar = (StackPane) stage.getScene().lookup(".account-avatar");
            assertNotNull(avatar);
            assertEquals(2, avatar.getChildren().size());
            assertEquals(64, ((ImageView) avatar.getChildren().get(0)).getImage().getWidth());
            assertEquals(1, launcher.homePage.getChildren().size());
            assertTrue(launcher.mainScrollPane.isFitToHeight());
            assertTrue(stage.getScene().getRoot().lookupAll(".forest-status-strip").isEmpty());
            assertTrue(stage.getScene().getRoot().lookupAll(".forest-activity").isEmpty());
            Button switchInstance = (Button) stage.getScene().lookup("#home-switch-instance");
            Button upgrade = (Button) stage.getScene().lookup("#home-update-instance");
            assertNotNull(upgrade);
            assertNotNull(stage.getScene().lookup("#instance-update-progress"));
            launcher.setControlsBusy(true);
            assertTrue(switchInstance.isDisabled());
            assertTrue(upgrade.isDisabled());
            launcher.setControlsBusy(false);
            switchInstance.fire();
            assertEquals(AppView.VERSIONS, launcher.activeView);
            assertFalse(launcher.mainScrollPane.isFitToHeight());
            launcher.setActiveView(AppView.HOME);
            assertTrue(launcher.mainScrollPane.isFitToHeight());
            ((Button) stage.getScene().lookup("#top-account-button")).fire();
            assertEquals(AppView.SETTINGS, launcher.activeView);
            assertTrue(launcher.accountSettingsSelected);
        });
    }

    @Test
    void changingLanguageRefreshesTheHomeControlsEvenWithoutAnInstance() {
        interact(() -> {
            String locale = com.ecl.util.Messages.locale().toLanguageTag();
            try {
                launcher.versionCombo.setValue(null);
                launcher.switchLanguage("en");
                assertFalse(launcher.topAuthBadgeLabel.getText().isBlank());
                assertFalse(launcher.selectedVersionTitleLabel.getText().isBlank());
                assertEquals("Play", launcher.launchBtn.getText());
                Button upgrade = (Button) stage.getScene().lookup("#home-update-instance");
                assertEquals("Upgrade all", upgrade.getText());
                assertTrue(upgrade.isDisabled());
            } finally {
                launcher.switchLanguage(locale);
            }
        });
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
                assertEquals(2, launcher.authTypeCombo.getItems().size());
                assertFalse(launcher.authTypeCombo.getItems().contains("YGGDRASIL"));
                assertEquals(LauncherUI.AUTH_OFFLINE,
                        new LauncherAuthController(launcher).normalizeAuthType("YGGDRASIL"));
                launcher.authTypeCombo.setValue(LauncherUI.AUTH_MICROSOFT);
                assertTrue(launcher.microsoftAccountCombo.isVisible());
                assertFalse(launcher.usernameField.isVisible());
                launcher.authTypeCombo.setValue(LauncherUI.AUTH_OFFLINE);
                launcher.usernameField.setText("ExplorerTestPlayer");
                ((Button) stage.getScene().lookup("#account-apply")).fire();
                assertEquals("ExplorerTestPlayer", launcher.settingsManager.get(ECLConfig.KEY_USERNAME));
                launcher.setActiveView(AppView.HOME);
                assertEquals("ExplorerTestPlayer", launcher.topAuthBadgeLabel.getText());
                launcher.openAccountSettings();
                assertEquals("ExplorerTestPlayer", launcher.usernameField.getText());
                assertTrue(launcher.usernameField.isVisible());
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
