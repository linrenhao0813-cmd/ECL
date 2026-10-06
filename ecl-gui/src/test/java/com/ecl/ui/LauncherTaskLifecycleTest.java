package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ServerJarDownloader;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherTaskLifecycleTest extends ApplicationTest {
    private LauncherUI launcher;

    @Override
    public void start(Stage stage) {
        launcher = new LauncherUI();
        launcher.start(new Stage());
    }

    @Override
    public void stop() {
        if (launcher != null) {
            launcher.stop();
            if (launcher.primaryStage != null) {
                launcher.primaryStage.close();
            }
        }
    }

    @Test
    void refreshRestoresTheStateTargetBeforeTheSelectorHasLoaded(@TempDir Path root) throws Exception {
        String first = "a-target-" + UUID.randomUUID();
        String target = "z-target-" + UUID.randomUUID();
        Path firstMetadata = createProfile(root, first);
        Path targetMetadata = createProfile(root, target);
        java.io.File previousRoot = launcher.gameDir;
        String previousTarget = launcher.getSelectedVersion();
        ComboBox<String> previousSelector = launcher.versionCombo;
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                launcher.setLaunchTarget(target);
                launcher.versionCombo = new ComboBox<>();
                launcher.versionActions.restoreVersionComboItems(target);
                launcher.versionActions.refreshVersions();
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> WaitForAsyncUtils.asyncFx(() -> target.equals(launcher.versionCombo.getValue())).get());
            interact(() -> assertEquals(target, new LauncherUiFacadeAdapter(launcher).selectedVersion()));
        } finally {
            interact(() -> {
                launcher.versionCombo = previousSelector;
                launcher.gameDir = previousRoot;
                launcher.setLaunchTarget(previousTarget);
            });
            deleteMetadata(firstMetadata);
            deleteMetadata(targetMetadata);
        }
    }

    @Test
    void newlyInstalledContentBecomesTheLaunchTargetBeforeCatalogRefresh() {
        String previousTarget = launcher.getSelectedVersion();
        interact(() -> {
            try {
                String newPack = "new-pack-" + UUID.randomUUID();
                assertFalse(launcher.versionCombo.getItems().contains(newPack));
                launcher.versionActions.syncLaunchVersionToContent(newPack);
                assertEquals(newPack, launcher.getSelectedVersion());
                assertEquals(newPack, new LauncherUiFacadeAdapter(launcher).selectedVersion());
            } finally {
                launcher.setLaunchTarget(previousTarget);
            }
        });
    }

    @Test
    void successfulInstanceRetryRunsCompletionAndSelectsTheInstance(@TempDir Path root) throws Exception {
        String target = "retry-instance-" + UUID.randomUUID();
        Path metadata = createProfile(root, target);
        VersionManager previousManager = launcher.versionManager;
        java.io.File previousRoot = launcher.gameDir;
        String previousTarget = launcher.getSelectedVersion();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<String> completed = new AtomicReference<>();
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                launcher.versionManager = new VersionManager() {
                    @Override
                    public CompletableFuture<Boolean> ensureVersionDownloadedAsync(String version) {
                        return attempts.incrementAndGet() == 1
                                ? CompletableFuture.failedFuture(new IOException("first attempt failed"))
                                : CompletableFuture.completedFuture(true);
                    }
                };
                new InstanceInstallWorkflow(launcher).install(target, LoaderChoice.VANILLA, null, null,
                        new InstanceInstallWorkflow.Listener() {
                            @Override public void onStarted() { started.incrementAndGet(); }
                            @Override public void onStatus(String message) { }
                            @Override public void onProgress(long downloaded, long total) { }
                            @Override public void onComplete(String profileId) { completed.set(profileId); }
                            @Override public void onFailure(String message) { failed.incrementAndGet(); }
                        });
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> failed.get() == 1);
            interact(() -> {
                var failedTask = launcher.downloadTaskCenter.snapshots().stream()
                        .filter(task -> task.title().contains(target)).findFirst().orElseThrow();
                assertNotNull(launcher.downloadTaskCenter.retry(failedTask.id()));
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> target.equals(completed.get()));
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> WaitForAsyncUtils.asyncFx(() -> launcher.versionCombo.getItems().contains(target)).get());
            interact(() -> {
                assertEquals(2, started.get());
                assertEquals(target, launcher.getSelectedVersion());
                assertFalse(launcher.versionCombo.isDisabled());
            });
        } finally {
            interact(() -> {
                launcher.versionManager = previousManager;
                launcher.gameDir = previousRoot;
                launcher.versionCombo.getItems().remove(target);
                launcher.setLaunchTarget(previousTarget);
            });
            deleteMetadata(metadata);
        }
    }

    @Test
    void queuedContentCancellationAndRetryRestoreTheSharedControls(@TempDir Path root) throws Exception {
        DownloadTaskCenter previousCenter = launcher.downloadTaskCenter;
        CountDownLatch release = new CountDownLatch(1);
        try (DownloadTaskCenter center = blockedCenter(release)) {
            AtomicReference<DownloadTaskCenter.TaskHandle<?>> queued = new AtomicReference<>();
            Button search = new Button();
            Button install = new Button();
            ComboBox<String> target = new ComboBox<>();
            interact(() -> {
                launcher.downloadTaskCenter = center;
                queued.set(new ContentDownloadWorkflow(launcher).downloadSelectedContent(
                        launcher.contentTargets.get(2), new ContentProject("project", "project", "Resource pack", "", "", 0, 0),
                        new ContentVersion("version", "", "1", "release"),
                        new ContentInstance("instance", "1.21.1", null, UUID.randomUUID(), root.toFile()),
                        root.toFile(), new Label(), new ProgressBar(), search, install, target,
                        new AtomicLong()));
                assertTrue(launcher.versionCombo.isDisabled());
                assertEquals(DownloadTaskCenter.Status.QUEUED, queued.get().snapshot().status());
                queued.get().cancel();
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertFalse(launcher.versionCombo.isDisabled());
                assertFalse(search.isDisabled());
                assertFalse(install.isDisabled());
                assertFalse(target.isDisabled());
                var retry = queued.get().retry();
                assertNotNull(retry);
                assertTrue(launcher.versionCombo.isDisabled());
                retry.cancel();
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> assertFalse(launcher.versionCombo.isDisabled()));
        } finally {
            release.countDown();
            interact(() -> launcher.downloadTaskCenter = previousCenter);
        }
    }

    @Test
    void queuedServerDownloadCancellationRestoresThePageControls(@TempDir Path root) throws Exception {
        DownloadTaskCenter previousCenter = launcher.downloadTaskCenter;
        CountDownLatch release = new CountDownLatch(1);
        var download = ServerJarDownloadPage.class.getDeclaredMethod("download", ServerJarDownloader.ServerArtifact.class,
                java.io.File.class, Label.class, ProgressBar.class, Button.class, Button.class, ComboBox.class,
                ComboBox.class, Button.class, AtomicLong.class);
        download.setAccessible(true);
        try (DownloadTaskCenter center = blockedCenter(release)) {
            Button button = new Button();
            Button folder = new Button();
            ComboBox<String> versions = new ComboBox<>();
            ComboBox<VersionManager.VersionCategory> categories = new ComboBox<>();
            Button refresh = new Button();
            interact(() -> {
                launcher.downloadTaskCenter = center;
                try {
                    download.invoke(new ServerJarDownloadPage(launcher),
                            new ServerJarDownloader.ServerArtifact("server-test", "", "", 1, List.of()), root.toFile(),
                            new Label(), new ProgressBar(), button, folder, versions, categories, refresh, new AtomicLong());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                assertTrue(button.isDisabled());
                center.snapshots().stream().filter(task -> task.title().startsWith("Server JAR"))
                        .forEach(task -> center.cancel(task.id()));
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertFalse(button.isDisabled());
                assertFalse(folder.isDisabled());
                assertFalse(versions.isDisabled());
                assertFalse(categories.isDisabled());
                assertFalse(refresh.isDisabled());
            });
        } finally {
            release.countDown();
            interact(() -> launcher.downloadTaskCenter = previousCenter);
        }
    }

    private static DownloadTaskCenter blockedCenter(CountDownLatch release) throws Exception {
        DownloadTaskCenter center = new DownloadTaskCenter(1);
        CountDownLatch started = new CountDownLatch(1);
        center.submit("blocker", context -> {
            started.countDown();
            release.await();
            return null;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        return center;
    }

    private static Path createProfile(Path root, String id) throws IOException {
        Path metadata = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                {"id":"%s","type":"release","mainClass":"net.minecraft.client.main.Main"}
                """.formatted(id));
        Files.createDirectories(root.resolve("versions").resolve(id));
        return metadata;
    }

    private void deleteMetadata(Path metadata) throws IOException {
        Files.deleteIfExists(metadata);
        Files.deleteIfExists(metadata.getParent());
        launcher.versionManager.invalidateLocalVersionProfiles();
    }
}
