package com.ecl.ui;

import com.ecl.backup.WorldBackupService;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerDialogTest extends ApplicationTest {
    private LauncherUI ui;
    private Path instances;
    private Stage backup;

    @Override
    public void start(Stage stage) {
        ui = new LauncherUI() {
            @Override File resolveVersionGameDir(String id) {
                return instances == null ? super.resolveVersionGameDir(id) : instances.resolve(id).toFile();
            }
        };
        ui.start(new Stage());
    }

    @Override
    public void stop() {
        if (backup != null) backup.close();
        ui.stop();
        ui.primaryStage.close();
    }

    @Test
    void explicitTargetStaysFixedForCreateAndRestoreAfterLaunchTargetChanges(@TempDir Path root) throws Exception {
        instances = root.resolve("instances");
        Path first = instances.resolve("A/saves/world/level.dat");
        Path second = instances.resolve("B/saves/world/level.dat");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "untouched A");
        Files.writeString(second, "original B");
        ui.worldBackupService = new WorldBackupService(root.resolve("backups"));
        interact(() -> {
            ui.setLaunchTarget("A");
            ui.showBackupManagerDialog("B");
            backup = Window.getWindows().stream().filter(window -> window instanceof Stage stage
                    && stage.getTitle().equals(Messages.format("backup.title", "B")))
                    .map(Stage.class::cast).findFirst().orElseThrow();
            ui.setLaunchTarget("C");
            button("backup-create").fire();
        });
        await(() -> !button("backup-create").isDisabled() && !list().getItems().isEmpty());
        assertTrue(ui.worldBackupService.listBackups("A").isEmpty());
        assertEquals(1, ui.worldBackupService.listBackups("B").size());
        Files.writeString(second, "changed B");
        interact(() -> {
            list().getSelectionModel().selectFirst();
            Platform.runLater(() -> Window.getWindows().stream().filter(window -> window != backup
                    && window != ui.primaryStage && window.isShowing()).toList().forEach(window -> {
                        DialogPane pane = (DialogPane) window.getScene().getRoot();
                        ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    }));
            button("backup-restore").fire();
        });
        await(() -> !button("backup-create").isDisabled());
        assertEquals("original B", Files.readString(second));
        assertEquals("untouched A", Files.readString(first));
        interact(() -> {
            backup.getScene().getRoot().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                    false, false, false, false));
            assertFalse(backup.isShowing());
        });
    }

    private Button button(String id) { return (Button) backup.getScene().lookup("#" + id); }
    private ListView<?> list() { return (ListView<?>) backup.getScene().lookup("#backup-list"); }
    private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(condition).get());
    }
}
