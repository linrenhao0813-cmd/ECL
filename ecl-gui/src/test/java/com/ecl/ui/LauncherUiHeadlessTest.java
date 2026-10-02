package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.util.Messages;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherUiHeadlessTest extends ApplicationTest {
    private LauncherUI launcher;
    private Stage stage;

    @Test
    void wizardListRefreshDoesNotSelectItsFirstInstalledInstance(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        String id = "wizard-selection-" + java.util.UUID.randomUUID();
        java.nio.file.Path metadata = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        java.nio.file.Files.createDirectories(metadata.getParent());
        java.nio.file.Files.writeString(metadata, """
                {"id":"%s","type":"release","mainClass":"net.minecraft.client.main.Main"}
                """.formatted(id));
        java.nio.file.Files.createDirectories(root.resolve("versions").resolve(id));
        java.io.File previousGameDir = launcher.gameDir;
        String previousTarget = launcher.getSelectedVersion();
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                launcher.setLaunchTarget(null);
                launcher.versionActions.restoreVersionComboItems(null, false);
            });
            org.testfx.util.WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                    () -> org.testfx.util.WaitForAsyncUtils.asyncFx(() -> launcher.versionCombo.getItems().contains(id)).get());
            interact(() -> {
                assertEquals(java.util.List.of(id), launcher.versionCombo.getItems());
                assertEquals(null, launcher.getSelectedVersion());
                assertEquals(null, launcher.versionCombo.getValue());
                assertEquals("", launcher.settingsManager.get(ECLConfig.KEY_SELECTED_VERSION));
            });
        } finally {
            interact(() -> {
                launcher.gameDir = previousGameDir;
                launcher.versionCombo.getItems().remove(id);
                launcher.setLaunchTarget(previousTarget);
            });
            java.nio.file.Files.deleteIfExists(metadata);
            java.nio.file.Files.deleteIfExists(metadata.getParent());
            launcher.versionManager.invalidateLocalVersionProfiles();
        }
    }

    @Test
    void recoveryLoginButtonsFollowAuthenticationBusyState() {
        interact(() -> {
            launcher.openAccountSettings();
            Button relogin = (Button) stage.getScene().lookup("#account-relogin");
            Button addAnother = (Button) stage.getScene().lookup("#account-add-another");
            try {
                launcher.setControlsBusy(true);
                assertTrue(relogin.isDisabled());
                assertTrue(addAnother.isDisabled());
            } finally {
                launcher.setControlsBusy(false);
            }
            assertFalse(relogin.isDisabled());
            assertFalse(addAnother.isDisabled());
        });
    }

    @Test
    void gameExitRefreshesControlsOnFxThread() throws Exception {
        Process process = runningProcess();
        java.util.concurrent.atomic.AtomicBoolean refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean offThread = new java.util.concurrent.atomic.AtomicBoolean();
        javafx.beans.value.ChangeListener<String> listener = (observable, previous, value) -> {
            refreshed.set(true);
            offThread.set(offThread.get() || !javafx.application.Platform.isFxApplicationThread());
        };
        String previousTarget = launcher.getSelectedVersion();
        interact(() -> {
            launcher.setLaunchTarget("monitor-thread-test");
            launcher.clearLaunchFailure();
            launcher.registerActiveGameProcess(process, "monitor-thread-test");
            launcher.updateRuntimeSummary();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertTrue(launcher.launchBtn.isDisabled());
            launcher.launchBtn.textProperty().addListener(listener);
        });
        try {
            Thread monitor = Thread.ofPlatform().name("test-game-monitor").start(() -> launcher.unregisterActiveGameProcess(process));
            monitor.join(5000);
            assertFalse(monitor.isAlive());
            org.testfx.util.WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS, refreshed::get);
            assertFalse(offThread.get(), "game exit must update controls only on the FX thread");
            interact(() -> assertFalse(launcher.launchBtn.isDisabled()));
        } finally {
            interact(() -> {
                launcher.launchBtn.textProperty().removeListener(listener);
                launcher.setLaunchTarget(previousTarget);
            });
        }
    }

    private static Process runningProcess() {
        return new Process() {
            @Override
            public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override
            public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override
            public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override
            public int waitFor() { return 0; }
            @Override
            public int exitValue() { return 0; }
            @Override
            public void destroy() { }
            @Override
            public boolean isAlive() { return true; }
        };
    }

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

    @Test
    void shellExposesSidebarInstanceBarAndTaskDock() {
        interact(() -> {
            Scene scene = stage.getScene();
            assertNotNull(scene.lookup(".nav-rail-vertical"), "left navigation rail is missing");
            assertEquals(AppView.values().length,
                    scene.getRoot().lookupAll(".nav-button-vertical").size());
            assertNotNull(scene.lookup("#instance-bar"), "instance bar is missing");
            assertNotNull(scene.lookup("#instance-selector"), "instance selector is missing");
            assertNotNull(scene.lookup("#status-bar"), "status bar is missing");
            assertNotNull(scene.lookup("#task-entry-button"), "task entry is missing");
            assertNotNull(scene.lookup("#shared-download-progress"), "shared progress bar is missing");
            assertNotNull(scene.lookup("#task-detail-panel"), "task detail panel is missing");
            assertNotNull(scene.lookup("#home-instance-settings"), "instance settings entry is missing");
        });
    }

    @Test
    void removedInstanceActionsStayAbsentAndTaskEntryDrivesTheSharedShell() {
        interact(() -> {
            Scene scene = stage.getScene();
            launcher.setActiveView(AppView.HOME);
            assertNull(scene.lookup("#instance-bar-manage"));
            assertNull(scene.lookup("#instance-bar-refresh"));
            launcher.setActiveView(AppView.VERSIONS);
            assertEquals(AppView.VERSIONS, launcher.activeView);
            assertNull(scene.lookup("#instance-install-new"));
            assertNotNull(scene.lookup("#installed-instance-list"));
            assertNotNull(scene.lookup("#instance-list-refresh"));

            javafx.scene.layout.VBox detail =
                    (javafx.scene.layout.VBox) scene.lookup("#task-detail-panel");
            assertNotNull(detail);
            assertFalse(detail.isVisible());
            ((Button) scene.lookup("#task-entry-button")).fire();
            assertTrue(detail.isVisible());
            assertTrue(detail.isManaged());
            ((Button) scene.lookup("#task-entry-button")).fire();
            assertFalse(detail.isVisible());

            launcher.setActiveView(AppView.HOME);
        });
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
            String previousTarget = launcher.getSelectedVersion();
            java.util.List<String> previousItems = new java.util.ArrayList<>(launcher.versionCombo.getItems());
            try {
                launcher.versionCombo.getItems().clear();
                launcher.setLaunchTarget(null);
                launcher.switchLanguage("en");
                assertFalse(launcher.topAuthBadgeLabel.getText().isBlank());
                assertFalse(launcher.selectedVersionTitleLabel.getText().isBlank());
                // Without a selected instance the primary action installs a game instead of launching.
                assertEquals("Install game", launcher.launchBtn.getText());
                Button upgrade = (Button) stage.getScene().lookup("#home-update-instance");
                assertEquals("Upgrade all", upgrade.getText());
                assertTrue(upgrade.isDisabled());
            } finally {
                launcher.switchLanguage(locale);
                launcher.versionCombo.getItems().setAll(previousItems);
                launcher.setLaunchTarget(previousTarget);
            }
        });
    }

    @Test
    void primaryLaunchActionTracksTheActualLaunchState() {
        interact(() -> {
            String previousTarget = launcher.getSelectedVersion();
            java.util.List<String> previousItems = new java.util.ArrayList<>(launcher.versionCombo.getItems());
            try {
                launcher.versionCombo.getItems().clear();
                launcher.setLaunchTarget(null);
                launcher.updateRuntimeSummary();
                assertEquals(Messages.get("home.installGame"), launcher.launchBtn.getText());

                launcher.versionCombo.getItems().add("visual-instance-for-launch-state");
                launcher.updateRuntimeSummary();
                assertEquals(Messages.get("home.chooseInstance"), launcher.launchBtn.getText());

                launcher.setLaunchTarget("visual-instance-for-launch-state");
                launcher.updateRuntimeSummary();
                assertEquals(GuiMessages.get("forest.launch"), launcher.launchBtn.getText());
                assertFalse(launcher.launchBtn.isDisabled());
            } finally {
                launcher.versionCombo.getItems().setAll(previousItems);
                launcher.setLaunchTarget(previousTarget);
                launcher.updateRuntimeSummary();
            }
        });
    }

    @Test
    void contentPageRestoresTheCategoryItWasLeftOn() {
        interact(() -> {
            String previousKey = launcher.contentCategoryKey;
            try {
                launcher.setActiveView(AppView.DOWNLOADS);
                launcher.contentCategoryKey = "server";
                launcher.setActiveView(AppView.HOME);
                launcher.setActiveView(AppView.DOWNLOADS);

                assertEquals("server", launcher.contentCategoryKey,
                        "returning to the content page must reopen the remembered category");
                assertFalse(stage.getScene().getRoot()
                                .lookupAll(".content-library-nav-item-active").isEmpty(),
                        "the remembered category must be highlighted");
            } finally {
                launcher.contentCategoryKey = previousKey;
                launcher.setActiveView(AppView.HOME);
            }
        });
    }

    @Test
    void taskPanelExposesTaskListAndDetailSummary() {
        interact(() -> {
            assertNotNull(stage.getScene().lookup("#task-list"));
            javafx.scene.control.ListView<?> list =
                    (javafx.scene.control.ListView<?>) stage.getScene().lookup("#task-list");
            assertNotNull(list.getPlaceholder(), "empty task list needs a placeholder");
            assertNotNull(stage.getScene().lookup("#task-detail-text"));
        });
    }

    @Test
    void launchTargetAndViewedInstanceStayIndependent() {
        interact(() -> {
            String previousTarget = launcher.getSelectedVersion();
            try {
                launcher.setLaunchTarget("target-instance-a");
                launcher.instanceSelection.setViewedInstance("viewed-instance-b");
                assertEquals("target-instance-a", launcher.getSelectedVersion());
                assertEquals("viewed-instance-b", launcher.instanceSelection.viewedInstance());
                // Browsing another instance must not silently retarget the next launch.
                assertNotEquals(launcher.getSelectedVersion(), launcher.instanceSelection.viewedInstance());
            } finally {
                launcher.instanceSelection.setViewedInstance(null);
                launcher.setLaunchTarget(previousTarget);
            }
        });
    }

    @Test
    void settingsPageSeparatesConfigurationScopes() {
        interact(() -> {
            launcher.setActiveView(AppView.SETTINGS);
            var tabs = (javafx.scene.control.TabPane) stage.getScene().lookup("#settings-tabs");
            assertNotNull(tabs);
            assertEquals(5, tabs.getTabs().size(),
                    "settings must separate general, downloads, defaults, account and diagnostics");
            assertTrue(tabs.getTabs().stream().noneMatch(tab -> "settings-instance-tab".equals(tab.getId())));
            launcher.setActiveView(AppView.HOME);
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
