package com.ecl.ui;

import com.ecl.ECLConfig;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceSettingsNavigationTest extends ApplicationTest {
    @TempDir
    Path gameRoot;
    private LauncherUI launcher;
    private Stage stage;
    private String first;
    private String second;
    private String previousTarget;
    private final List<Path> metadata = new ArrayList<>();

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = new Stage();
        launcher.start(stage);
        previousTarget = launcher.settingsManager.get(ECLConfig.KEY_SELECTED_VERSION);
        launcher.pageFactory.setSettingsChoiceProvider(tab -> SettingsPageGuard.Choice.CANCEL);
    }

    @Override
    public void stop() throws Exception {
        if (launcher != null) {
            launcher.settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, previousTarget);
            launcher.settingsManager.save();
            launcher.stop();
        }
        if (stage != null) stage.close();
        for (Path file : metadata) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        }
    }

    @Test
    void settingsOmitsInstanceSubmenuAndInstancesNavigationOpensTheEditor() throws Exception {
        prepareInstances(false);
        interact(() -> {
            launcher.setActiveView(AppView.SETTINGS);
            TabPane settingsTabs = (TabPane) stage.getScene().lookup("#settings-tabs");
            assertEquals(5, settingsTabs.getTabs().size());
            assertTrue(settingsTabs.getTabs().stream().noneMatch(tab -> "settings-instance-tab".equals(tab.getId())));
            assertNull(stage.getScene().lookup("#installed-instance-list"));
            launcher.setActiveView(AppView.HOME);
            assertNull(stage.getScene().lookup("#home-instance-settings"));
            launcher.setActiveView(AppView.VERSIONS);
            assertEquals(AppView.VERSIONS, launcher.activeView);
        });
        awaitSelection(first);
        interact(() -> {
            selectDetailTab(0);
            assertNotNull(control("instance-display-save", Button.class));
            assertEquals(1, control("instance-detail-tabs", TabPane.class).getTabs().size());
            assertNull(control("instance-config-save", Button.class));
            assertEquals(first, list().getSelectionModel().getSelectedItem());
        });
    }
    @Test
    void compactSettingsKeepsTheInstanceListUsable() throws Exception {
        prepareInstances(true);
        interact(() -> {
            stage.setWidth(1180);
            stage.setHeight(720);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertTrue(list().getHeight() >= 160, "compact instance list must keep room for its rows");
        });
    }

    @Test
    void cancellingInstanceSwitchOrNavigationKeepsEditsAndTheirTarget() throws Exception {
        prepareInstances(true);
        interact(() -> {
            selectDetailTab(0);
            control("instance-display-name", TextField.class).setText("Unsaved name");
            list().getSelectionModel().select(second);
            assertEquals(first, list().getSelectionModel().getSelectedItem());
            assertEquals("Unsaved name", control("instance-display-name", TextField.class).getText());
            launcher.setLaunchTarget(second);
            assertEquals(first, list().getSelectionModel().getSelectedItem());
            assertEquals("Unsaved name", control("instance-display-name", TextField.class).getText());
            selectDetailTab(0);
            selectDetailTab(0);
            assertEquals("Unsaved name", control("instance-display-name", TextField.class).getText());
            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.VERSIONS, launcher.activeView);
        });
    }

    @Test
    void savingDisplayOnDepartureWritesOnlyTheViewedInstance() throws Exception {
        prepareInstances(true);
        interact(() -> {
            control("instance-display-name", TextField.class).setText("Scoped display name");
            launcher.pageFactory.setSettingsChoiceProvider(tab -> SettingsPageGuard.Choice.SAVE);
            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.HOME, launcher.activeView);
            assertEquals("Scoped display name", launcher.instanceDisplay.get(first).displayName());
            assertEquals("", launcher.instanceDisplay.get(second).displayName());
        });
    }
    @Test
    void displayEditsAreGuardedAndCanBeDiscarded() throws Exception {
        prepareInstances(true);
        interact(() -> {
            control("instance-display-name", TextField.class).setText("Unsaved name");
            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.VERSIONS, launcher.activeView);
            launcher.pageFactory.setSettingsChoiceProvider(tab -> SettingsPageGuard.Choice.DISCARD);
            list().getSelectionModel().select(second);
            assertEquals(second, list().getSelectionModel().getSelectedItem());
            assertEquals("", launcher.instanceDisplay.get(first).displayName());
            selectDetailTab(0);
            control("instance-display-name", TextField.class).setText("Another draft");
            launcher.pageFactory.setSettingsChoiceProvider(tab -> SettingsPageGuard.Choice.CANCEL);
            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.VERSIONS, launcher.activeView);
            launcher.pageFactory.setSettingsChoiceProvider(tab -> SettingsPageGuard.Choice.DISCARD);
            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.HOME, launcher.activeView);
        });
    }

    private void prepareInstances(boolean settings) throws Exception {
        first = createInstance();
        second = createInstance();
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            launcher.versionManager.invalidateLocalVersionProfiles();
            launcher.setLaunchTarget(first);
            launcher.instanceSelection.setViewedInstance(first);
            if (settings) launcher.pageFactory.openInstanceSettings(first);
            else launcher.setActiveView(AppView.VERSIONS);
        });
        awaitSelection(first);
    }

    private String createInstance() throws Exception {
        String id = "settings-instance-" + UUID.randomUUID();
        Path file = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"%s","type":"release","mainClass":"net.minecraft.client.main.Main"}
                """.formatted(id));
        metadata.add(file);
        Files.createDirectories(gameRoot.resolve("versions").resolve(id));
        return id;
    }

    private void awaitSelection(String id) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            return list() != null && !list().isDisabled()
                    && id.equals(list().getSelectionModel().getSelectedItem());
        }).get());
    }

    @SuppressWarnings("unchecked")
    private ListView<String> list() {
        return (ListView<String>) stage.getScene().lookup("#installed-instance-list");
    }

    private void selectDetailTab(int index) {
        control("instance-detail-tabs", TabPane.class).getSelectionModel().select(index);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private <T extends Node> T control(String id, Class<T> type) {
        return type.cast(stage.getScene().lookup("#" + id));
    }
}
