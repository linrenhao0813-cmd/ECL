package com.ecl.ui;

import com.ecl.backup.BackupEntry;
import com.ecl.backup.WorldBackupService;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Explicit visual-QA entry point. It is not part of the regular test task;
 * run it through Gradle's captureLauncherUi task.
 */
public final class LauncherUiSnapshot {
    private static volatile Throwable captureFailure;

    private LauncherUiSnapshot() {
    }

    public static void main(String[] args) {
        Application.launch(SnapshotApplication.class, args);
        if (captureFailure != null) {
            throw new IllegalStateException("Failed to capture the launcher UI", captureFailure);
        }
    }

    private static void capture(Scene scene) throws Exception {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        if ("settings".equalsIgnoreCase(System.getProperty("ecl.snapshot.mode"))
                && scene.getRoot() instanceof ScrollPane scrollPane) {
            scrollPane.setVvalue(1.0);
            scene.getRoot().layout();
        }

        int width = Math.max(1, (int) Math.ceil(scene.getWidth()));
        int height = Math.max(1, (int) Math.ceil(scene.getHeight()));
        WritableImage snapshot = new WritableImage(width, height);
        scene.snapshot(snapshot);

        int[] pixels = new int[width * height];
        snapshot.getPixelReader().getPixels(
                0,
                0,
                width,
                height,
                PixelFormat.getIntArgbPreInstance(),
                pixels,
                0,
                width);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        image.setRGB(0, 0, width, height, pixels, 0, width);

        Path output = Path.of(System.getProperty(
                "ecl.snapshot.path",
                "build/visual-qa/ecl-home.png")).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IllegalStateException("No PNG writer is available");
        }
        File file = output.toFile();
        System.out.println("ECL_UI_SNAPSHOT=" + file.getAbsolutePath());
    }

    public static final class SnapshotApplication extends LauncherUI {
        @Override
        public void start(Stage stage) {
            super.start(stage);
            stage.setIconified(false);

            Scene captureScene;
            try {
                captureScene = prepareCaptureScene(stage);
            } catch (Throwable error) {
                captureFailure = error;
                error.printStackTrace(System.err);
                stage.close();
                Platform.exit();
                return;
            }

            PauseTransition settle = new PauseTransition(Duration.millis(900));
            settle.setOnFinished(event -> {
                try {
                    capture(captureScene);
                } catch (Throwable error) {
                    captureFailure = error;
                    error.printStackTrace(System.err);
                } finally {
                    for (Window window : Window.getWindows().toArray(Window[]::new)) {
                        window.hide();
                    }
                    Platform.exit();
                }
            });
            settle.play();
        }

        private Scene prepareCaptureScene(Stage primaryStage) throws Exception {
            String mode = System.getProperty("ecl.snapshot.mode", "home");
            if ("versions".equalsIgnoreCase(mode)) {
                showAppView("VERSIONS");
                return primaryStage.getScene();
            }
            if ("settings".equalsIgnoreCase(mode)) {
                Method settingsDialog = LauncherUI.class.getDeclaredMethod("showSettingsDialog");
                settingsDialog.setAccessible(true);
                settingsDialog.invoke(this);
                Scene scene = findSecondaryScene(primaryStage, "Settings dialog did not open");
                if (scene.getRoot() instanceof ScrollPane scrollPane) {
                    scrollPane.setVvalue(1.0);
                }
                return scene;
            }
            if ("backups".equalsIgnoreCase(mode)) {
                String profileId = "visual-backup-instance";
                Field comboField = LauncherUI.class.getDeclaredField("versionCombo");
                comboField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ComboBox<String> combo = (ComboBox<String>) comboField.get(this);
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);

                Field gameDirField = LauncherUI.class.getDeclaredField("gameDir");
                gameDirField.setAccessible(true);
                File gameRoot = (File) gameDirField.get(this);
                Path saves = gameRoot.toPath().resolve("versions").resolve(profileId)
                        .resolve("saves/Visual World");
                Files.createDirectories(saves);
                Files.writeString(saves.resolve("level.dat"), "visual snapshot fixture");
                WorldBackupService backupService = new WorldBackupService();
                if (backupService.listBackups(profileId).isEmpty()) {
                    backupService.createBackup(profileId, "1.21.4", saves.getParent().getParent(),
                            EnumSet.of(BackupEntry.Content.SAVES), null);
                }

                Method backupDialog = LauncherUI.class.getDeclaredMethod("showBackupManagerDialog");
                backupDialog.setAccessible(true);
                backupDialog.invoke(this);
                return findSecondaryScene(primaryStage, "Backup dialog did not open");
            }
            return primaryStage.getScene();
        }

        private Scene findSecondaryScene(Stage primaryStage, String failureMessage) {
            return Window.getWindows().stream()
                    .filter(window -> window != primaryStage && window.isShowing())
                    .map(Window::getScene)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(failureMessage));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void showAppView(String name) throws Exception {
            Class<? extends Enum> viewType = (Class<? extends Enum>) Class.forName(
                    "com.ecl.ui.LauncherUI$AppView");
            Object view = Enum.valueOf(viewType, name);
            Method setActiveView = LauncherUI.class.getDeclaredMethod("setActiveView", viewType);
            setActiveView.setAccessible(true);
            setActiveView.invoke(this, view);
        }
    }
}
