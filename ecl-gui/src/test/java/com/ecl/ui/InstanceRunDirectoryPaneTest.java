package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.game.InstanceLaunchProfile;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceRunDirectoryPaneTest extends ApplicationTest {
    @TempDir
    Path gameRoot;
    private LauncherUI launcher;
    private Stage stage;
    private InstanceRunDirectoryPane pane;

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = new Stage();
        launcher.start(stage);
    }

    @Override
    public void stop() {
        if (launcher != null) launcher.stop();
        if (stage != null) stage.close();
    }

    @Test
    void directoryModesSaveOnlyTheViewedInstanceAndSurviveReload() throws Exception {
        String viewed = fixture(false);
        Path root = gameRoot.resolve("versions").resolve(viewed);
        launcher.controller.instanceLaunchProfiles().save(root, InstanceLaunchProfile.defaults());
        Path launchFile = launcher.controller.instanceLaunchProfiles().profileFile(root);
        String launchBefore = Files.readString(launchFile);
        InstanceGameSettingsStore store = new InstanceGameSettingsStore();
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            launcher.instanceSelection.setLaunchTarget("different-target");
            showPane(viewed);
            int defaultMemory = launcher.settingsManager.get(ECLConfig.KEY_MAX_MEMORY_MB);
            selectMode(2);
            pathField().setText(gameRoot.resolve("custom-run").toString());
            saveButton().fire();
            assertEquals(InstanceGameSettings.custom(gameRoot.resolve("custom-run")), load(store, root));
            pane.load(viewed);
            assertEquals(2, mode().getSelectionModel().getSelectedIndex());
            assertEquals(gameRoot.resolve("custom-run").toString(), pathField().getText());
            selectMode(1);
            saveButton().fire();
            assertEquals(InstanceGameSettings.isolated(), load(store, root));
            selectMode(0);
            saveButton().fire();
            assertEquals(InstanceGameSettings.inherited(), load(store, root));
            assertEquals("different-target", launcher.getSelectedVersion());
            assertEquals(defaultMemory, launcher.settingsManager.get(ECLConfig.KEY_MAX_MEMORY_MB));
        });
        assertEquals(launchBefore, Files.readString(launchFile));
        assertFalse(Files.exists(store.settingsFile(gameRoot.resolve("versions/different-target"))));
    }

    @Test
    void invalidCustomPathPreservesSavedDirectoryAndDraft() throws Exception {
        String viewed = fixture(false);
        Path root = gameRoot.resolve("versions").resolve(viewed);
        InstanceGameSettingsStore store = new InstanceGameSettingsStore();
        store.save(root, InstanceGameSettings.isolated());
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            showPane(viewed);
            selectMode(2);
            for (String invalid : new String[] {"", "relative/path"}) {
                pathField().setText(invalid);
                saveButton().fire();
                assertEquals(InstanceGameSettings.isolated(), load(store, root));
                assertEquals(invalid, pathField().getText());
            }
        });
    }

    @Test
    void modpackDirectoryCannotBeOverridden() throws Exception {
        String viewed = fixture(true);
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            showPane(viewed);
            assertTrue(mode().isDisabled());
            assertTrue(saveButton().isDisabled());
        });
        assertFalse(Files.exists(new InstanceGameSettingsStore().settingsFile(gameRoot.resolve("versions").resolve(viewed))));
    }

    private String fixture(boolean modpack) throws IOException {
        String id = "directory-test-" + UUID.randomUUID();
        Path metadata = ECLConfig.getVersionsDir().toPath().resolve(id);
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve(id + ".json"),
                "{\"id\":\"" + id + "\",\"eclModpackName\":\"" + (modpack ? "Test pack" : "") + "\"}");
        return id;
    }

    private void showPane(String viewed) {
        pane = new InstanceRunDirectoryPane(launcher, () -> { });
        Scene scene = new Scene(pane, 600, 300);
        scene.getStylesheets().add(getClass().getResource("/css/launcher.css").toExternalForm());
        LauncherThemeManager.applyToScene(scene);
        stage.setScene(scene);
        pane.load(viewed);
        pane.applyCss();
        pane.layout();
    }

    private ComboBox<?> mode() {
        return (ComboBox<?>) pane.lookup("#instance-directory-mode");
    }

    private void selectMode(int index) {
        mode().getSelectionModel().select(index);
    }

    private TextField pathField() {
        return (TextField) pane.lookup("#instance-directory-path");
    }

    private Button saveButton() {
        return (Button) pane.lookup("#instance-directory-save");
    }

    private static InstanceGameSettings load(InstanceGameSettingsStore store, Path root) {
        try {
            return store.load(root);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
