package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.game.InstanceLaunchProfile;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsDialogTest extends ApplicationTest {
    @TempDir
    Path gameRoot;
    private LauncherUI launcher;
    private Stage stage;
    private Stage dialog;

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = new Stage();
        launcher.start(stage);
    }

    @Override
    public void stop() {
        if (dialog != null) dialog.close();
        if (launcher != null) launcher.stop();
        if (stage != null) stage.close();
    }

    @Test
    void dialogUsesDarkBackgroundCardsAndFieldsAndContainsOnlyGlobalControls() {
        interact(() -> {
            Scene scene = openDialog();
            assertTrue(scene.getRoot().getStyleClass().containsAll(List.of("scene-root", "theme-dark")));
            assertDark((Region) scene.lookup(".surface"));
            assertDark((Region) scene.lookup(".settings-dialog-title-bar"));
            assertDark((Region) scene.lookup(".check-box > .box"));
            assertEquals(javafx.stage.StageStyle.UNDECORATED, dialog.getStyle());
            assertDark((Region) scene.lookup("#settings-global-game-dir"));
            assertFalse(scene.getRoot().lookupAll(".combo-box").iterator().hasNext(), "instance isolation and release channel belong to their pages");
            assertEquals(6, scene.getRoot().lookupAll(".text-field").size(), "no duplicate Java, memory or JVM fields");
        });
    }

    @Test
    void savingGlobalBehaviorDoesNotWriteLaunchDefaultsOrTheSelectedInstanceProfile() throws Exception {
        Path instanceRoot = gameRoot.resolve("versions/dialog-scope-test");
        Path profileFile = launcher.controller.instanceLaunchProfiles().profileFile(instanceRoot);
        launcher.controller.instanceLaunchProfiles().save(instanceRoot, InstanceLaunchProfile.defaults());
        String before = Files.readString(profileFile);
        interact(() -> {
            SettingsManager original = launcher.settingsManager;
            TestSettingsManager manager = new TestSettingsManager(true);
            try {
                launcher.settingsManager = manager;
                launcher.gameDir = gameRoot.toFile();
                launcher.instanceSelection.setLaunchTarget("dialog-scope-test");
                manager.set(ECLConfig.KEY_JAVA_PATH, "unchanged-default-java");
                manager.set(ECLConfig.KEY_MAX_MEMORY_MB, 3072);
                manager.set(ECLConfig.KEY_JVM_ARGS, "-Ddefault=true");
                manager.remove(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE);
                manager.remove(ECLConfig.KEY_MOD_RELEASE_CHANNEL);
                launcher.setActiveView(AppView.SETTINGS);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                stage.getScene().lookup("#settings-language").requestFocus();
                Scene scene = openDialog();
                field(scene, "settings-global-width").setText("1440");
                ((Button) scene.lookup("#settings-global-save")).fire();
                assertFalse(dialog.isShowing());
                assertEquals(1440, manager.get(ECLConfig.KEY_GAME_WIDTH));
                assertEquals("unchanged-default-java", manager.get(ECLConfig.KEY_JAVA_PATH));
                assertEquals(3072, manager.get(ECLConfig.KEY_MAX_MEMORY_MB));
                assertEquals("-Ddefault=true", manager.get(ECLConfig.KEY_JVM_ARGS));
                assertFalse(manager.has(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE));
                assertFalse(manager.has(ECLConfig.KEY_MOD_RELEASE_CHANNEL));
            } finally {
                launcher.settingsManager = original;
                manager.close();
            }
        });
        interact(() -> assertEquals(stage.getScene().lookup("#settings-language"), stage.getScene().getFocusOwner()));
        assertEquals(before, Files.readString(profileFile));
    }

    @Test
    void failedGlobalSavePreservesRuntimeValuesAndKeepsTheDraftOpen() {
        interact(() -> {
            SettingsManager original = launcher.settingsManager;
            TestSettingsManager manager = new TestSettingsManager(false);
            int width = launcher.gameWidth;
            try {
                launcher.settingsManager = manager;
                launcher.gameDir = gameRoot.toFile();
                manager.set(ECLConfig.KEY_GAME_WIDTH, width);
                manager.remove(ECLConfig.KEY_GAME_DIR);
                Scene scene = openDialog();
                field(scene, "settings-global-width").setText("invalid");
                ((Button) scene.lookup("#settings-global-save")).fire();
                assertEquals(0, manager.attempts);
                field(scene, "settings-global-width").setText("1440");
                ((Button) scene.lookup("#settings-global-save")).fire();
                assertTrue(dialog.isShowing());
                assertEquals(1, manager.attempts);
                assertEquals(width, launcher.gameWidth);
                assertEquals(width, manager.get(ECLConfig.KEY_GAME_WIDTH));
                assertFalse(manager.has(ECLConfig.KEY_GAME_DIR));
                assertEquals("1440", field(scene, "settings-global-width").getText());
            } finally {
                launcher.settingsManager = original;
                manager.close();
            }
        });
    }

    private Scene openDialog() {
        new SettingsDialog(launcher).show();
        dialog = Window.getWindows().stream().filter(window -> window != stage && window instanceof Stage)
                .map(window -> (Stage) window).findFirst().orElseThrow();
        Scene scene = dialog.getScene();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return scene;
    }

    private static TextField field(Scene scene, String id) {
        return (TextField) scene.lookup("#" + id);
    }

    private static void assertDark(Region region) {
        Color color = (Color) region.getBackground().getFills().getFirst().getFill();
        assertTrue(color.getBrightness() < 0.25, "expected forest dark background, got " + color);
    }

    private static final class TestSettingsManager extends SettingsManager {
        private final boolean succeeds;
        private int attempts;

        TestSettingsManager(boolean succeeds) {
            this.succeeds = succeeds;
        }

        @Override
        public synchronized boolean save() {
            attempts++;
            return succeeds;
        }
    }
}
