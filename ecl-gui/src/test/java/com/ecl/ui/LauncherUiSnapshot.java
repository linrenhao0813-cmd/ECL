package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.backup.BackupEntry;
import com.ecl.backup.WorldBackupService;
import com.ecl.config.SettingsManager;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import java.io.IOException;
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
        String mode = System.getProperty("ecl.snapshot.mode");
        if ("settings".equalsIgnoreCase(mode) || "loader-choice".equalsIgnoreCase(mode)) {
            ScrollPane scrollPane = findScrollPane(scene.getRoot());
            if (scrollPane != null) {
                scrollPane.setVvalue(1.0);
                scene.getRoot().layout();
            }
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

    private static ScrollPane findScrollPane(Node node) {
        if (node instanceof ScrollPane scrollPane) {
            return scrollPane;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ScrollPane found = findScrollPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static final class SnapshotApplication extends LauncherUI {
        @Override
        public void start(Stage stage) {
            if ("initial-dark".equalsIgnoreCase(System.getProperty("ecl.snapshot.mode"))) {
                SettingsManager settings = new SettingsManager();
                settings.load();
                settings.set(ECLConfig.KEY_THEME, "DARK");
                settings.save();
            }
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

            String mode = System.getProperty("ecl.snapshot.mode", "home");
            long settleMillis = mode.toLowerCase(java.util.Locale.ROOT).endsWith("-online") ? 6_000 : 900;
            PauseTransition settle = new PauseTransition(Duration.millis(settleMillis));
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
            if ("initial-dark".equalsIgnoreCase(mode)) {
                if (!primaryStage.getScene().getRoot().getStyleClass().contains("theme-dark")) {
                    throw new IllegalStateException("Initial launcher scene did not apply the dark theme");
                }
                return primaryStage.getScene();
            }
            if ("versions".equalsIgnoreCase(mode)) {
                showAppView("VERSIONS");
                return primaryStage.getScene();
            }
            if ("downloads".equalsIgnoreCase(mode)) {
                showAppView("DOWNLOADS");
                return primaryStage.getScene();
            }
            if ("servers".equalsIgnoreCase(mode)
                    || "servers-dark".equalsIgnoreCase(mode)
                    || "servers-en".equalsIgnoreCase(mode)) {
                if ("servers-dark".equalsIgnoreCase(mode)) {
                    Field settingsField = LauncherUI.class.getDeclaredField("settingsManager");
                    settingsField.setAccessible(true);
                    SettingsManager settings = (SettingsManager) settingsField.get(this);
                    settings.set(ECLConfig.KEY_THEME, "DARK");
                    Method applyTheme = LauncherUI.class.getDeclaredMethod("applyTheme", String.class);
                    applyTheme.setAccessible(true);
                    applyTheme.invoke(this, "DARK");
                }
                if ("servers-en".equalsIgnoreCase(mode)) {
                    Method switchLanguage = LauncherUI.class.getDeclaredMethod(
                            "switchLanguage", String.class);
                    switchLanguage.setAccessible(true);
                    switchLanguage.invoke(this, "en");
                }
                showAppView("SERVERS");
                return primaryStage.getScene();
            }
            if ("loader-choice".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile(
                        "visual-vanilla-loader-choice", "", "1.20.6");
                ComboBox<String> versionCombo = versionCombo();
                if (!versionCombo.getItems().contains(profileId)) versionCombo.getItems().add(profileId);
                versionCombo.setValue(profileId);
                Field loaderField = LauncherUI.class.getDeclaredField("loaderChoiceCombo");
                loaderField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ComboBox<Object> loaderCombo = (ComboBox<Object>) loaderField.get(this);
                loaderCombo.getItems().stream()
                        .filter(item -> "Fabric".equals(item.toString()))
                        .findFirst().ifPresent(loaderCombo::setValue);
                Field settingsPaneField = LauncherUI.class.getDeclaredField("instanceSettingsPane");
                settingsPaneField.setAccessible(true);
                ((javafx.scene.control.TitledPane) settingsPaneField.get(this)).setExpanded(true);
                return primaryStage.getScene();
            }
            if ("skin-upload".equalsIgnoreCase(mode)) {
                Field authTypeField = LauncherUI.class.getDeclaredField("authTypeCombo");
                authTypeField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ComboBox<String> authType = (ComboBox<String>) authTypeField.get(this);
                authType.setValue("MICROSOFT");
                Field settingsPaneField = LauncherUI.class.getDeclaredField("instanceSettingsPane");
                settingsPaneField.setAccessible(true);
                ((javafx.scene.control.TitledPane) settingsPaneField.get(this)).setExpanded(true);
                return primaryStage.getScene();
            }
            if ("modrinth-vanilla".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile("visual-vanilla-instance", "");
                ComboBox<String> combo = versionCombo();
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);
                showAppView("MODRINTH");
                return primaryStage.getScene();
            }
            if ("modrinth".equalsIgnoreCase(mode) || "modrinth-online".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile("visual-fabric-instance", "fabric");
                ComboBox<String> combo = versionCombo();
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);
                showAppView("MODRINTH");
                return primaryStage.getScene();
            }
            if ("content-shader".equalsIgnoreCase(mode)
                    || "content-resourcepack".equalsIgnoreCase(mode)
                    || "content-modpack".equalsIgnoreCase(mode)
                    || "content-server".equalsIgnoreCase(mode)
                    || "content-shader-online".equalsIgnoreCase(mode)
                    || "content-resourcepack-online".equalsIgnoreCase(mode)
                    || "content-modpack-online".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile("visual-content-instance", "");
                ComboBox<String> combo = versionCombo();
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);
                showAppView("MODRINTH");
                primaryStage.getScene().getRoot().applyCss();
                String normalizedMode = mode.toLowerCase(java.util.Locale.ROOT);
                int categoryIndex = normalizedMode.startsWith("content-shader") ? 1
                        : normalizedMode.startsWith("content-resourcepack") ? 2
                        : normalizedMode.startsWith("content-server") ? 4 : 3;
                if (categoryIndex == 4) {
                    createVisualServerVersion("1.21.4");
                }
                Button category = primaryStage.getScene().getRoot()
                        .lookupAll(".content-library-nav-item").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .toList().get(categoryIndex);
                category.fire();
                return primaryStage.getScene();
            }
            if ("settings-page".equalsIgnoreCase(mode)) {
                showAppView("SETTINGS");
                return primaryStage.getScene();
            }
            if ("settings-page-dark".equalsIgnoreCase(mode)) {
                Field settingsField = LauncherUI.class.getDeclaredField("settingsManager");
                settingsField.setAccessible(true);
                SettingsManager settings = (SettingsManager) settingsField.get(this);
                settings.set(ECLConfig.KEY_THEME, "DARK");
                showAppView("SETTINGS");
                Method applyTheme = LauncherUI.class.getDeclaredMethod("applyTheme", String.class);
                applyTheme.setAccessible(true);
                applyTheme.invoke(this, "DARK");
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

        private String createVisualProfile(String profileId, String loader) throws IOException {
            return createVisualProfile(profileId, loader, "1.21.4");
        }

        private String createVisualProfile(String profileId, String loader,
                                           String minecraftVersion) throws IOException {
            Path profileDirectory = ECLConfig.getVersionsDir().toPath().resolve(profileId);
            Files.createDirectories(profileDirectory);
            String loaderProperty = loader.isBlank() ? ""
                    : ",\n  \"eclModLoader\": \"" + loader + "\"";
            Files.writeString(profileDirectory.resolve(profileId + ".json"), """
                    {
                      "id": "%s",
                      "eclMinecraftVersion": "%s"%s
                    }
                    """.formatted(profileId, minecraftVersion, loaderProperty));
            return profileId;
        }

        private void createVisualServerVersion(String versionId) throws IOException {
            Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve(versionId);
            Files.createDirectories(versionDirectory);
            Files.writeString(versionDirectory.resolve(versionId + ".json"), """
                    {
                      "id":"%s",
                      "downloads":{"server":{
                        "url":"https://piston-data.mojang.com/v1/objects/visual/server.jar",
                        "sha1":"0123456789abcdef0123456789abcdef01234567",
                        "size":52142812
                      }}
                    }
                    """.formatted(versionId));
        }

        @SuppressWarnings("unchecked")
        private ComboBox<String> versionCombo() throws ReflectiveOperationException {
            Field comboField = LauncherUI.class.getDeclaredField("versionCombo");
            comboField.setAccessible(true);
            return (ComboBox<String>) comboField.get(this);
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
                    "com.ecl.ui.AppView");
            Object view = Enum.valueOf(viewType, name);
            Method setActiveView = LauncherUI.class.getDeclaredMethod("setActiveView", viewType);
            setActiveView.setAccessible(true);
            setActiveView.invoke(this, view);
        }
    }
}
