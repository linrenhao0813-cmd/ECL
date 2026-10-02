package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.util.Messages;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDetailsPaneTest extends ApplicationTest {
    @TempDir
    Path gameRoot;
    private LauncherUI launcher;
    private Stage stage;
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
        if (stage != null) stage.close();
    }

    @Test
    void overviewHasNoAdvancedMenusAndDoesNotRewriteLaunchConfiguration() throws Exception {
        Path instanceRoot = gameRoot.resolve("versions/details-test-instance");
        launcher.controller.instanceLaunchProfiles().save(instanceRoot, InstanceLaunchProfile.defaults());
        Path profileFile = launcher.controller.instanceLaunchProfiles().profileFile(instanceRoot);
        String before = Files.readString(profileFile);
        interact(() -> {
            InstanceDetailsPane pane = showDetails();
            assertEquals(1, pane.detailTabs().getTabs().size());
            assertEquals(Messages.get("instances.tab.overview"), pane.detailTabs().getTabs().getFirst().getText());
            assertNull(pane.lookup("#instance-config-save"));
            assertNull(pane.lookup("#instance-delete"));
            assertNull(pane.lookup("#instance-directory-mode"));
            ((TextField) pane.lookup("#instance-display-name")).setText("Overview test");
            ((Button) pane.lookup("#instance-display-save")).fire();
            assertFalse(pane.hasUnsavedChanges());
            assertEquals("Overview test", launcher.instanceDisplay.get("details-test-instance").displayName());
        });
        assertEquals(before, Files.readString(profileFile));
    }

    @Test
    void changingTheLaunchTargetPreservesDisplayDraftsAndUpdatesTheTargetButton() {
        interact(() -> {
            InstanceDetailsPane pane = showDetails();
            TextField name = (TextField) pane.lookup("#instance-display-name");
            name.setText("Unsaved display name");
            launcher.setLaunchTarget("different-target");
            assertEquals("Unsaved display name", name.getText());
            assertTrue(pane.hasUnsavedChanges());
            Button target = (Button) pane.lookup("#instance-set-target");
            assertFalse(target.isDisabled());
            target.fire();
            assertEquals("details-test-instance", launcher.getSelectedVersion());
            assertTrue(target.isDisabled());
            assertEquals("Unsaved display name", name.getText());
        });
    }

    private InstanceDetailsPane showDetails() {
        launcher.gameDir = gameRoot.toFile();
        InstanceDetailsPane pane = new InstanceDetailsPane(launcher, () -> { });
        pane.setInstance("details-test-instance");
        launcher.workspacePane.getChildren().setAll(pane);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        return pane;
    }
}
