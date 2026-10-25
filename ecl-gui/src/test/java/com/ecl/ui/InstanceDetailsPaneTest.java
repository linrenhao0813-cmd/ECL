package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void profileReadLockDoesNotBlockFxAndEscapeClosesBeforeLateCompletion() throws Exception {
        Path instanceRoot = gameRoot.resolve("versions/details-test-instance");
        var store = launcher.controller.instanceLaunchProfiles();
        store.save(instanceRoot, InstanceLaunchProfile.defaults());
        byte[] original = Files.readAllBytes(store.profileFile(instanceRoot));
        java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Window> opened = new AtomicReference<>();
        interact(this::showDetails);
        synchronized (store) {
            Platform.runLater(() -> InstanceLaunchProfileDialog.show(launcher, "details-test-instance",
                    (Button) stage.getScene().lookup("#instance-launch-settings"), saved::incrementAndGet));
            assertTrue(WaitForAsyncUtils.asyncFx(() -> {
                Window dialog = Window.getWindows().stream()
                        .filter(window -> window != stage && window.isShowing() && window.getScene() != null
                                && window.getScene().lookup("#instance-launch-profile-form") != null)
                        .findFirst().orElseThrow();
                opened.set(dialog);
                dialog.getScene().getRoot().fireEvent(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                        false, false, false, false));
                return !dialog.isShowing();
            }).get(2, java.util.concurrent.TimeUnit.SECONDS));
        }
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> assertFalse(opened.get().isShowing()));
        assertEquals(0, saved.get());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(store.profileFile(instanceRoot)));
    }

    @Test
    void repeatedDeleteActionQueuesOnlyOneConfirmedTask() throws Exception {
        java.lang.reflect.Field executor = MainController.class.getDeclaredField("backgroundExecutor");
        executor.setAccessible(true);
        Object previous = executor.get(launcher.controller);
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        var queue = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            @Override public void execute(Runnable action) { submissions.incrementAndGet(); }
        };
        executor.set(launcher.controller, queue);
        try {
            interact(() -> {
                InstanceDetailsPane pane = showDetails();
                Platform.runLater(() -> Window.getWindows().stream()
                        .filter(window -> window != stage && window.isShowing()).toList().forEach(window -> {
                            javafx.scene.control.DialogPane dialog = (javafx.scene.control.DialogPane) window.getScene().getRoot();
                            ((Button) dialog.lookupButton(javafx.scene.control.ButtonType.OK)).fire();
                        }));
                try {
                    var deletion = InstanceDetailsPane.class.getDeclaredMethod("deleteInstance");
                    deletion.setAccessible(true);
                    deletion.invoke(pane);
                    deletion.invoke(pane);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                assertEquals(1, submissions.get());
            });
        } finally {
            executor.set(launcher.controller, previous);
        }
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
            assertNotNull(pane.lookup("#instance-launch-settings"));
            assertNotNull(pane.lookup("#instance-check-repair"));
            assertNotNull(pane.lookup("#instance-restore-last-update"));
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
    void changingTheLaunchTargetPreservesDisplayDraftsWithoutTargetControls() {
        interact(() -> {
            InstanceDetailsPane pane = showDetails();
            TextField name = (TextField) pane.lookup("#instance-display-name");
            name.setText("Unsaved display name");
            launcher.setLaunchTarget("different-target");
            assertEquals("Unsaved display name", name.getText());
            assertTrue(pane.hasUnsavedChanges());
            assertNull(pane.lookup("#instance-set-target"));
            assertNull(pane.lookup(".instance-target-badge"));
            assertNull(stage.getScene().lookup("#instance-bar-meta"));
            assertEquals("different-target", launcher.getSelectedVersion());
            assertEquals("Unsaved display name", name.getText());
        });
    }

    @Test
    void maintenanceRefreshPreservesDisplayDraftButSelectingAnotherInstanceLoadsItsValues() {
        interact(() -> {
            InstanceDetailsPane pane = showDetails();
            TextField name = (TextField) pane.lookup("#instance-display-name");
            name.setText("Keep this draft");
            pane.refresh();
            assertEquals("Keep this draft", name.getText());
            assertTrue(pane.hasUnsavedChanges());
            pane.setInstance("another-draft-instance");
            assertEquals(launcher.instanceDisplay.get("another-draft-instance").displayName(), name.getText());
            assertFalse(pane.hasUnsavedChanges());
        });
    }

    @Test
    void deletionUsesConfiguredGameVersionsAndKeepsSharedDataAndMetadata() throws Exception {
        String id = "details-test-instance";
        Path metadata = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{\"id\":\"details-test-instance\",\"libraries\":[]}");
        Path instance = gameRoot.resolve("versions").resolve(id);
        Files.createDirectories(instance);
        Files.writeString(instance.resolve("options.txt"), "instance options");
        Path sharedSave = gameRoot.resolve("saves/world/level.dat");
        Files.createDirectories(sharedSave.getParent());
        Files.writeString(sharedSave, "shared world");
        AtomicReference<InstanceDetailsPane> pane = new AtomicReference<>();
        try {
            interact(() -> {
                pane.set(showDetails());
                launcher.setLaunchTarget(id);
            });
            var delete = InstanceDetailsPane.class.getDeclaredMethod("deleteInstanceFiles", String.class);
            delete.setAccessible(true);
            delete.invoke(pane.get(), id);
            WaitForAsyncUtils.waitForFxEvents();
            assertFalse(Files.exists(instance));
            assertTrue(Files.isRegularFile(metadata));
            assertEquals("shared world", Files.readString(sharedSave));
            interact(() -> assertNull(launcher.getSelectedVersion()));
        } finally {
            Files.deleteIfExists(metadata);
        }
    }

    @Test
    void backupDialogButtonsUseEachSupportedLocale() {
        java.util.Locale previous = Messages.locale();
        try {
            for (java.util.Locale locale : java.util.List.of(java.util.Locale.ENGLISH,
                    java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.TRADITIONAL_CHINESE)) {
                interact(() -> {
                    Messages.setLocale(locale);
                    launcher.setLaunchTarget("translation-test");
                    new BackupManagerDialog(launcher).show();
                    Stage dialog = (Stage) Window.getWindows().stream().filter(window -> window != stage
                            && window instanceof Stage candidate
                            && candidate.getTitle().equals(Messages.format("backup.title", "translation-test")))
                            .findFirst().orElseThrow();
                    try {
                        var labels = dialog.getScene().getRoot().lookupAll(".button").stream()
                                .filter(Button.class::isInstance).map(Button.class::cast).map(Button::getText).toList();
                        assertTrue(labels.contains(Messages.get("backup.create")));
                        assertTrue(labels.contains(Messages.get("backup.restore")));
                        assertTrue(labels.contains(Messages.get("backup.close")));
                        if (locale.equals(java.util.Locale.ENGLISH)) assertTrue(labels.contains("Create backup"));
                    } finally {
                        dialog.close();
                    }
                });
            }
        } finally {
            Messages.setLocale(previous);
        }
    }

    @Test
    void restoringDefaultsThenCancellingDoesNotWriteOrChangeTheLaunchTarget() throws Exception {
        Path instanceRoot = gameRoot.resolve("versions/details-test-instance");
        InstanceLaunchProfile profile = new InstanceLaunchProfile(1,
                InstanceLaunchProfile.JavaMode.AUTO, "", InstanceLaunchProfile.MemoryMode.CUSTOM,
                4096, java.util.List.of("-Dinstance=kept"));
        launcher.controller.instanceLaunchProfiles().save(instanceRoot, profile);
        Path profileFile = launcher.controller.instanceLaunchProfiles().profileFile(instanceRoot);
        String before = Files.readString(profileFile);
        interact(() -> {
            InstanceDetailsPane pane = showDetails();
            launcher.setLaunchTarget("different-target");
            Platform.runLater(() -> ((Button) pane.lookup("#instance-launch-settings")).fire());
        });
        WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS, () ->
                WaitForAsyncUtils.asyncFx(() -> Window.getWindows().stream()
                        .filter(window -> window != stage && window.isShowing() && window.getScene() != null)
                        .map(window -> window.getScene().lookup("#instance-launch-reset-defaults"))
                        .anyMatch(node -> node != null && !node.isDisabled())).get());
        interact(() -> {
            Window dialog = Window.getWindows().stream()
                    .filter(window -> window != stage && window.isShowing()
                            && window.getScene().lookup("#instance-launch-reset-defaults") != null)
                    .findFirst().orElseThrow();
            ((Button) dialog.getScene().lookup("#instance-launch-reset-defaults")).fire();
            ((Button) dialog.getScene().lookup("#instance-launch-cancel")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before, Files.readString(profileFile));
        assertEquals("different-target", launcher.getSelectedVersion());
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
