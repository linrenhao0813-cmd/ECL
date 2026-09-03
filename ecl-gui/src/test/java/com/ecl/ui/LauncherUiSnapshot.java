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
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
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
            if ("versions".equalsIgnoreCase(mode) || "versions-dark".equalsIgnoreCase(mode)
                    || "instance-install-dark".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile("1.21.8", "", "1.21.8");
                createVisualProfile("1.21.7", "", "1.21.7");
                ComboBox<String> combo = versionCombo();
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);
                createVisualVersionManifest();
                if (mode.toLowerCase(java.util.Locale.ROOT).endsWith("-dark")) {
                    applySnapshotTheme("DARK");
                }
                showAppView("DOWNLOADS");
                if ("instance-install-dark".equalsIgnoreCase(mode)) {
                    scheduleInstallerPreview(primaryStage);
                }
                return primaryStage.getScene();
            }
            if ("saves".equalsIgnoreCase(mode) || "saves-ai".equalsIgnoreCase(mode)) {
                createVisualProfile("visual-save-vanilla", "", "1.20.1");
                createVisualProfile("visual-save-fabric", "fabric", "1.20.1");
                Field gameDirField = LauncherUIView.class.getDeclaredField("gameDir");
                gameDirField.setAccessible(true);
                File gameRoot = (File) gameDirField.get(this);
                createVisualSave(gameRoot.toPath().resolve("saves/Alpine Base"));
                createVisualSave(gameRoot.toPath().resolve("versions/visual-save-fabric/saves/Modded Valley"));
                showAppView("SAVES");
                if ("saves-ai".equalsIgnoreCase(mode)) {
                    selectAssistantTab(primaryStage);
                }
                return primaryStage.getScene();
            }
            if ("downloads".equalsIgnoreCase(mode)) {
                showAppView("DOWNLOADS");
                selectDownloadCategory(primaryStage, 7);
                return primaryStage.getScene();
            }
            if ("servers".equalsIgnoreCase(mode)
                    || "servers-dark".equalsIgnoreCase(mode)
                    || "servers-en".equalsIgnoreCase(mode)) {
                if ("servers-dark".equalsIgnoreCase(mode)) {
                    applySnapshotTheme("DARK");
                }
                if ("servers-en".equalsIgnoreCase(mode)) {
                    Method switchLanguage = LauncherUIView.class.getDeclaredMethod(
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
                showAppView("DOWNLOADS");
                Field loaderField = LauncherUIView.class.getDeclaredField("loaderChoiceCombo");
                loaderField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ComboBox<Object> loaderCombo = (ComboBox<Object>) loaderField.get(this);
                loaderCombo.getItems().stream()
                        .filter(item -> "Fabric".equals(item.toString()))
                        .findFirst().ifPresent(loaderCombo::setValue);
                return primaryStage.getScene();
            }
            if ("skin-upload".equalsIgnoreCase(mode)) {
                showAppView("DOWNLOADS");
                Field authTypeField = LauncherUIView.class.getDeclaredField("authTypeCombo");
                authTypeField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ComboBox<String> authType = (ComboBox<String>) authTypeField.get(this);
                authType.setValue("MICROSOFT");
                return primaryStage.getScene();
            }
            if ("modrinth-vanilla".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile("visual-vanilla-instance", "");
                ComboBox<String> combo = versionCombo();
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);
                showAppView("DOWNLOADS");
                selectDownloadCategory(primaryStage, 1);
                return primaryStage.getScene();
            }
            if ("modrinth".equalsIgnoreCase(mode) || "modrinth-online".equalsIgnoreCase(mode)) {
                String profileId = createVisualProfile("visual-fabric-instance", "fabric");
                ComboBox<String> combo = versionCombo();
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);
                showAppView("DOWNLOADS");
                selectDownloadCategory(primaryStage, 1);
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
                showAppView("DOWNLOADS");
                primaryStage.getScene().getRoot().applyCss();
                String normalizedMode = mode.toLowerCase(java.util.Locale.ROOT);
                int categoryIndex = normalizedMode.startsWith("content-shader") ? 2
                        : normalizedMode.startsWith("content-resourcepack") ? 3
                        : normalizedMode.startsWith("content-server") ? 5 : 4;
                if (categoryIndex == 5) {
                    createVisualServerVersion("1.21.4");
                }
                selectDownloadCategory(primaryStage, categoryIndex);
                return primaryStage.getScene();
            }
            if ("settings-page".equalsIgnoreCase(mode)) {
                showAppView("SETTINGS");
                return primaryStage.getScene();
            }
            if ("settings-page-dark".equalsIgnoreCase(mode)) {
                applySnapshotTheme("DARK");
                showAppView("SETTINGS");
                return primaryStage.getScene();
            }
            if ("settings".equalsIgnoreCase(mode)) {
                Method settingsDialog = LauncherUIView.class.getDeclaredMethod("showSettingsDialog");
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
                Field comboField = LauncherUIView.class.getDeclaredField("versionCombo");
                comboField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ComboBox<String> combo = (ComboBox<String>) comboField.get(this);
                if (!combo.getItems().contains(profileId)) combo.getItems().add(profileId);
                combo.setValue(profileId);

                Field gameDirField = LauncherUIView.class.getDeclaredField("gameDir");
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

                Method backupDialog = LauncherUIView.class.getDeclaredMethod("showBackupManagerDialog");
                backupDialog.setAccessible(true);
                backupDialog.invoke(this);
                return findSecondaryScene(primaryStage, "Backup dialog did not open");
            }
            return primaryStage.getScene();
        }

        private static void selectAssistantTab(Stage stage) {
            javafx.scene.control.TabPane tabs = (javafx.scene.control.TabPane)
                    stage.getScene().lookup(".world-save-tabs");
            if (tabs == null || tabs.getTabs().size() < 2) {
                throw new IllegalStateException("AI Assistant tab was not rendered");
            }
            tabs.getSelectionModel().select(1);
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

        private void createVisualSave(Path directory) throws IOException {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("level.dat"), "visual snapshot fixture");
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
            Field comboField = LauncherUIView.class.getDeclaredField("versionCombo");
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

        private void applySnapshotTheme(String theme) throws Exception {
            Field settingsField = LauncherUIView.class.getDeclaredField("settingsManager");
            settingsField.setAccessible(true);
            SettingsManager settings = (SettingsManager) settingsField.get(this);
            settings.set(ECLConfig.KEY_THEME, theme);
            Method applyTheme = LauncherUIView.class.getDeclaredMethod("applyTheme", String.class);
            applyTheme.setAccessible(true);
            applyTheme.invoke(this, theme);
        }

        private void scheduleInstallerPreview(Stage stage) {
            PauseTransition openInstaller = new PauseTransition(Duration.millis(300));
            openInstaller.setOnFinished(event -> stage.getScene().getRoot()
                    .lookupAll(".instance-version-list").stream()
                    .filter(ListView.class::isInstance)
                    .map(node -> (ListView<?>) node)
                    .filter(list -> list.getItems().contains("1.21.7"))
                    .findFirst().ifPresent(list -> list.getSelectionModel()
                            .select(list.getItems().indexOf("1.21.7"))));
            PauseTransition chooseFabric = new PauseTransition(Duration.millis(520));
            chooseFabric.setOnFinished(event -> stage.getScene().getRoot()
                    .lookupAll(".instance-install-choice").stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .filter(button -> button.getUserData() == LoaderChoice.FABRIC)
                    .findFirst().ifPresent(ToggleButton::fire));
            openInstaller.play();
            chooseFabric.play();
        }

        private void createVisualVersionManifest() throws IOException {
            Path manifest = ECLConfig.getVersionsDir().toPath().resolve("version_manifest.json");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, """
                    {
                      "latest": {"release": "1.21.8", "snapshot": "26w34a"},
                      "versions": [
                        {"id":"1.21.8","type":"release","releaseTime":"2026-07-17T00:00:00Z"},
                        {"id":"1.21.7","type":"release","releaseTime":"2026-06-30T00:00:00Z"},
                        {"id":"1.21.6","type":"release","releaseTime":"2026-06-17T00:00:00Z"},
                        {"id":"1.21.5","type":"release","releaseTime":"2026-03-25T00:00:00Z"},
                        {"id":"1.21.4","type":"release","releaseTime":"2025-12-03T00:00:00Z"},
                        {"id":"1.21.3","type":"release","releaseTime":"2025-10-23T00:00:00Z"},
                        {"id":"26w34a","type":"snapshot","releaseTime":"2026-08-19T00:00:00Z"},
                        {"id":"26w33a","type":"snapshot","releaseTime":"2026-08-12T00:00:00Z"},
                        {"id":"26w32a","type":"snapshot","releaseTime":"2026-08-05T00:00:00Z"},
                        {"id":"26w31a","type":"snapshot","releaseTime":"2026-07-29T00:00:00Z"},
                        {"id":"26w30a","type":"snapshot","releaseTime":"2026-07-22T00:00:00Z"},
                        {"id":"26w14potato","type":"snapshot","releaseTime":"2026-04-01T00:00:00Z"},
                        {"id":"25w14craftmine","type":"snapshot","releaseTime":"2025-04-01T00:00:00Z"},
                        {"id":"24w14potato","type":"snapshot","releaseTime":"2024-04-01T00:00:00Z"},
                        {"id":"23w13a_or_b","type":"snapshot","releaseTime":"2023-04-01T00:00:00Z"},
                        {"id":"22w13oneblockatatime","type":"snapshot","releaseTime":"2022-04-01T00:00:00Z"},
                        {"id":"20w14infinite","type":"snapshot","releaseTime":"2020-04-01T00:00:00Z"}
                      ]
                    }
                    """);
            versionManager.refresh();
        }

        private void selectDownloadCategory(Stage primaryStage, int index) {
            primaryStage.getScene().getRoot().applyCss();
            Button category = primaryStage.getScene().getRoot()
                    .lookupAll(".content-library-nav-item").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .toList().get(index);
            category.fire();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void showAppView(String name) throws Exception {
            Class<? extends Enum> viewType = (Class<? extends Enum>) Class.forName(
                    "com.ecl.ui.AppView");
            Object view = Enum.valueOf(viewType, name);
            Method setActiveView = LauncherUIView.class.getDeclaredMethod("setActiveView", viewType);
            setActiveView.setAccessible(true);
            setActiveView.invoke(this, view);
        }
    }
}
