package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.performance.PerformancePreset;
import com.ecl.util.Messages;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDetailsPaneTest extends ApplicationTest {
    @TempDir
    Path gameRoot;

    private LauncherUI launcher;
    private Stage stage;
    private InstanceDetailsPane pane;
    private String savedLaunchTarget;

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = new Stage();
        launcher.start(stage);
        savedLaunchTarget = launcher.settingsManager.get(ECLConfig.KEY_SELECTED_VERSION);
    }

    @Override
    public void stop() {
        if (launcher != null) {
            launcher.settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, savedLaunchTarget);
            launcher.settingsManager.save();
            launcher.stop();
        }
        if (stage != null) {
            stage.close();
        }
    }

    @Test
    void automaticMemoryIgnoresThePreviousCustomValueAndPersistsAutomaticMode() {
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            saveProfile(customMemoryProfile(4096));
            showDetails();
            selectTab(1);
            RadioButton automatic = control("instance-memory-auto", RadioButton.class);
            TextField memory = control("instance-memory-value", TextField.class);
            assertEquals("4096", memory.getText());
            automatic.setSelected(true);
            assertTrue(memory.isDisabled());
            memory.setText("previous custom value is invalid");
            control("instance-config-save", Button.class).fire();
            assertEquals(InstanceLaunchProfile.MemoryMode.AUTO, loadProfile().memoryMode());
            assertEquals(ECLConfig.AUTO_MEMORY_MB, loadProfile().maxMemoryMb());
        });
    }

    @Test
    void customMemoryRequiresAValueAndPersistsTheSelectedAmount() {
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            saveProfile(InstanceLaunchProfile.defaults());
            showDetails();
            selectTab(1);
            control("instance-memory-custom", RadioButton.class).setSelected(true);
            TextField memory = control("instance-memory-value", TextField.class);
            assertFalse(memory.isDisabled());
            memory.setText("");
            control("instance-config-save", Button.class).fire();
            assertEquals(InstanceLaunchProfile.MemoryMode.AUTO, loadProfile().memoryMode());
            assertTrue(pane.lookupAll(".status-detail").stream()
                    .anyMatch(node -> node instanceof javafx.scene.control.Label label
                            && Messages.get("status.memoryInvalid").equals(label.getText())));
            memory.setText("6144");
            control("instance-config-save", Button.class).fire();
            assertEquals(InstanceLaunchProfile.MemoryMode.CUSTOM, loadProfile().memoryMode());
            assertEquals(6144, loadProfile().maxMemoryMb());
        });
    }

    @Test
    void missingProfileMigratesAndDisplaysConfiguredDefaults() {
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            String oldJava = launcher.settingsManager.get(ECLConfig.KEY_JAVA_PATH);
            int oldMemory = launcher.settingsManager.get(ECLConfig.KEY_MAX_MEMORY_MB);
            String oldArguments = launcher.settingsManager.get(ECLConfig.KEY_JVM_ARGS);
            try {
                String javaPath = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
                launcher.settingsManager.set(ECLConfig.KEY_JAVA_PATH, javaPath);
                launcher.settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, 4096);
                launcher.settingsManager.set(ECLConfig.KEY_JVM_ARGS, "-Dinstance.default=true");
                showDetails();
                selectTab(1);
                assertTrue(control("instance-java-custom", RadioButton.class).isSelected());
                assertEquals(javaPath, control("instance-java-path", TextField.class).getText());
                assertTrue(control("instance-memory-custom", RadioButton.class).isSelected());
                assertEquals("4096", control("instance-memory-value", TextField.class).getText());
                assertEquals("-Dinstance.default=true", control("instance-jvm-arguments", TextField.class).getText());
                assertEquals(List.of("-Dinstance.default=true"), loadProfile().customJvmArguments());
            } finally {
                launcher.settingsManager.set(ECLConfig.KEY_JAVA_PATH, oldJava);
                launcher.settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, oldMemory);
                launcher.settingsManager.set(ECLConfig.KEY_JVM_ARGS, oldArguments);
                launcher.settingsManager.save();
            }
        });
    }

    @Test
    void changingTargetRefreshesPermissionsAndGuardsPreviouslyCreatedActions() {
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            launcher.setLaunchTarget("details-test-instance");
            showDetails();
            selectTab(2);
            Button oldManage = control("instance-manage-mods", Button.class);
            assertFalse(control("instance-delete", Button.class).isDisabled());
            assertFalse(control("instance-reinstall", Button.class).isDisabled());
            launcher.setLaunchTarget("different-target");
            stage.getScene().getRoot().applyCss();
            assertTrue(control("instance-delete", Button.class).isDisabled());
            assertTrue(control("instance-reinstall", Button.class).isDisabled());
            assertTrue(control("instance-manage-mods", Button.class).isDisabled());
            AppView previousView = launcher.activeView;
            oldManage.getOnAction().handle(new ActionEvent(oldManage, oldManage));
            assertEquals(previousView, launcher.activeView,
                    "a retained callback must not manage mods for the new launch target");
            launcher.setLaunchTarget("details-test-instance");
            stage.getScene().getRoot().applyCss();
            assertFalse(control("instance-delete", Button.class).isDisabled());
            assertFalse(control("instance-reinstall", Button.class).isDisabled());
        });
    }

    @Test
    void reattachingThePaneUpdatesPermissionsWithoutResettingUnsavedConfig() {
        interact(() -> {
            launcher.gameDir = gameRoot.toFile();
            launcher.setLaunchTarget("details-test-instance");
            showDetails();
            selectTab(1);
            control("instance-jvm-arguments", TextField.class).setText("-Dunsaved=true");
            launcher.workspacePane.getChildren().clear();
            launcher.setLaunchTarget("different-target");
            launcher.workspacePane.getChildren().add(pane);
            stage.getScene().getRoot().applyCss();
            assertEquals("-Dunsaved=true", control("instance-jvm-arguments", TextField.class).getText());
            selectTab(2);
            assertTrue(control("instance-delete", Button.class).isDisabled());
        });
    }

    private void showDetails() {
        pane = new InstanceDetailsPane(launcher, () -> { });
        pane.setInstance("details-test-instance");
        launcher.workspacePane.getChildren().setAll(pane);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private void selectTab(int index) {
        control("instance-detail-tabs", TabPane.class).getSelectionModel().select(index);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private <T extends Node> T control(String id, Class<T> type) {
        return type.cast(pane.lookup("#" + id));
    }

    private InstanceLaunchProfile loadProfile() {
        try {
            return launcher.controller.instanceLaunchProfiles().load(
                    launcher.resolveVersionInstanceRoot("details-test-instance").toPath());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private void saveProfile(InstanceLaunchProfile profile) {
        try {
            launcher.controller.instanceLaunchProfiles().save(
                    launcher.resolveVersionInstanceRoot("details-test-instance").toPath(), profile);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static InstanceLaunchProfile customMemoryProfile(int memoryMb) {
        return new InstanceLaunchProfile(InstanceLaunchProfile.CURRENT_SCHEMA_VERSION,
                InstanceLaunchProfile.JavaMode.AUTO, "", PerformancePreset.BALANCED,
                InstanceLaunchProfile.MemoryMode.CUSTOM, memoryMb, true, List.of(), true, "default");
    }
}
