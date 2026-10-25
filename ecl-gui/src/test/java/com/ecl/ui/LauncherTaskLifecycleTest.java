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
    void closedTaskCenterDoesNotLeaveDownloadPreparationBusy() throws Exception {
        var original = launcher.downloadTaskCenter;
        var manager = launcher.versionManager;
        var closed = new DownloadTaskCenter();
        closed.close();
        var download = GameLaunchPreparation.class.getDeclaredMethod(
                "downloadAndLaunch", String.class, java.util.function.Consumer.class);
        download.setAccessible(true);
        var preparation = new GameLaunchPreparation(launcher, null);
        var preparing = GameLaunchPreparation.class.getDeclaredField("preparingVersion");
        preparing.setAccessible(true);
        preparing.set(preparation, "closed-center");
        try {
            interact(() -> {
                launcher.downloadTaskCenter = closed;
                launcher.versionManager = new VersionManager() {
                    @Override public VersionDownloadTarget resolveDownloadTarget(String id) {
                        return new VersionDownloadTarget(id, id, "https://example.com/version.json", "");
                    }
                };
                try {
                    download.invoke(preparation, "closed-center", (java.util.function.Consumer<String>) ignored -> { });
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                assertFalse(preparation.isPreparingVersion("closed-center"));
                assertFalse(launcher.versionCombo.isDisabled());
            });
        } finally {
            launcher.downloadTaskCenter = original;
            launcher.versionManager = manager;
        }
    }

    @Test
    void preparationTimeoutCancelsDownloadAndRestoresControls() throws Exception {
        com.ecl.download.DownloadService previousDownloader = launcher.downloader;
        VersionManager previousManager = launcher.versionManager;
        AtomicInteger launches = new AtomicInteger();
        CompletableFuture<Void> stalled = new CompletableFuture<>() {
            @Override
            public Void get(long timeout, TimeUnit unit) throws java.util.concurrent.TimeoutException {
                assertEquals(600, unit.toSeconds(timeout));
                throw new java.util.concurrent.TimeoutException("Stalled download");
            }
        };
        try (com.ecl.download.GameDownloader downloader = new com.ecl.download.GameDownloader() {
            @Override
            public synchronized java.util.concurrent.Future<?> downloadVersionAsync(String id, String url, String sha1) {
                return stalled;
            }
        }) {
            java.lang.reflect.Method download = GameLaunchPreparation.class.getDeclaredMethod(
                    "downloadAndLaunch", String.class, java.util.function.Consumer.class);
            download.setAccessible(true);
            interact(() -> {
                launcher.downloader = downloader;
                launcher.versionManager = new VersionManager() {
                    @Override
                    public VersionDownloadTarget resolveDownloadTarget(String version) {
                        return new VersionDownloadTarget(version, version, "https://piston-meta.mojang.com/test.json", "");
                    }
                };
                try {
                    download.invoke(new GameLaunchPreparation(launcher, null), "timeout-test",
                            (java.util.function.Consumer<String>) version -> launches.incrementAndGet());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> stalled.isCancelled());
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() ->
                    !launcher.versionCombo.isDisabled()
                            && launcher.downloadTaskCenter.snapshots().getLast().status() == DownloadTaskCenter.Status.FAILED).get());
            assertEquals(0, launches.get());
            assertTrue(launcher.downloadTaskCenter.snapshots().getLast().errorMessage()
                    .contains(GuiMessages.get("launch.downloadTimeout")));
        } finally {
            interact(() -> {
                launcher.downloader = previousDownloader;
                launcher.versionManager = previousManager;
            });
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
    void cancellationAfterServerPublicationCleansBeforeRetryAndReusesDestination(@TempDir Path root) throws Exception {
        java.io.File previousRoot = launcher.gameDir;
        String previousTarget = launcher.getSelectedVersion();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Path> destination = new AtomicReference<>();
        AtomicReference<String> completed = new AtomicReference<>();
        AtomicReference<String> taskId = new AtomicReference<>();
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                new ServerInstallWorkflow(launcher) {
                    @Override
                    Path installServer(String mc, LoaderChoice choice, String version, Path target,
                                       com.ecl.launcher.ModLoaderInstaller.Listener listener) throws IOException {
                        assertFalse(javafx.application.Platform.isFxApplicationThread());
                        assertFalse(Files.exists(target));
                        Files.createDirectory(target);
                        Files.writeString(target.resolve("server.jar"), "test installation");
                        if (attempts.incrementAndGet() == 1) {
                            destination.set(target);
                            String id = launcher.downloadTaskCenter.snapshots().getLast().id();
                            taskId.set(id);
                            assertTrue(launcher.downloadTaskCenter.cancel(id));
                        } else {
                            assertEquals(destination.get(), target);
                        }
                        return target;
                    }
                }.install("1.21.1", LoaderChoice.VANILLA, null, List.of(), new InstanceInstallWorkflow.Listener() {
                    @Override public void onStatus(String message) { }
                    @Override public void onProgress(long downloaded, long total) { }
                    @Override public void onComplete(String directory) { completed.set(directory); }
                    @Override public void onFailure(String message) { failed.incrementAndGet(); }
                });
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> failed.get() == 1
                    && launcher.downloadTaskCenter.canRetry(taskId.get()));
            assertFalse(Files.exists(destination.get()));
            interact(() -> assertNotNull(launcher.downloadTaskCenter.retry(taskId.get())));
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> completed.get() != null);
            assertEquals(destination.get().toString(), completed.get());
            assertTrue(Files.exists(destination.get().resolve("server.jar")));
            interact(() -> {
                assertEquals(2, attempts.get());
                assertEquals(previousTarget, launcher.getSelectedVersion());
                assertFalse(launcher.versionCombo.isDisabled());
            });
            assertFalse(Files.exists(root.resolve("versions")));
        } finally {
            interact(() -> launcher.gameDir = previousRoot);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void cancelledServerCleanupFailureIsVisibleAndDoesNotReportSuccess(@TempDir Path root) throws Exception {
        java.io.File previousRoot = launcher.gameDir;
        AtomicReference<Path> destination = new AtomicReference<>();
        AtomicReference<java.nio.channels.FileChannel> locked = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                new ServerInstallWorkflow(launcher) {
                    @Override
                    Path installServer(String mc, LoaderChoice choice, String version, Path target,
                                       com.ecl.launcher.ModLoaderInstaller.Listener listener) throws IOException {
                        Files.createDirectory(target);
                        Path jar = Files.writeString(target.resolve("server.jar"), "locked installation");
                        locked.set(java.nio.channels.FileChannel.open(jar, java.nio.file.StandardOpenOption.WRITE,
                                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));
                        destination.set(target);
                        assertTrue(launcher.downloadTaskCenter.cancel(launcher.downloadTaskCenter.snapshots().getLast().id()));
                        return target;
                    }
                }.install("1.21.1", LoaderChoice.VANILLA, null, List.of(), new InstanceInstallWorkflow.Listener() {
                    @Override public void onStatus(String message) { }
                    @Override public void onProgress(long downloaded, long total) { }
                    @Override public void onComplete(String directory) { throw new AssertionError("Unexpected success"); }
                    @Override public void onFailure(String message) { failure.set(message); }
                });
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> failure.get() != null);
            assertEquals(GuiMessages.get("instance.install.server.cleanupFailed", destination.get()), failure.get());
            assertTrue(Files.exists(destination.get().resolve("server.jar")));
            interact(() -> assertFalse(launcher.versionCombo.isDisabled()));
        } finally {
            if (locked.get() != null) locked.get().close();
            interact(() -> launcher.gameDir = previousRoot);
        }
    }

    @Test
    void failedServerInstallNeverDeletesDirectoryItDidNotReturn(@TempDir Path root) throws Exception {
        java.io.File previousRoot = launcher.gameDir;
        AtomicReference<Path> destination = new AtomicReference<>();
        AtomicInteger failed = new AtomicInteger();
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                new ServerInstallWorkflow(launcher) {
                    @Override
                    Path installServer(String mc, LoaderChoice choice, String version, Path target,
                                       com.ecl.launcher.ModLoaderInstaller.Listener listener) throws IOException {
                        Files.createDirectory(target);
                        Files.writeString(target.resolve("keep.txt"), "another owner");
                        destination.set(target);
                        throw new IOException("Target occupied before installation returned");
                    }
                }.install("1.21.1", LoaderChoice.VANILLA, null, List.of(), new InstanceInstallWorkflow.Listener() {
                    @Override public void onStatus(String message) { }
                    @Override public void onProgress(long downloaded, long total) { }
                    @Override public void onComplete(String directory) { throw new AssertionError("Unexpected success"); }
                    @Override public void onFailure(String message) { failed.incrementAndGet(); }
                });
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> failed.get() == 1);
            assertEquals("another owner", Files.readString(destination.get().resolve("keep.txt")));
        } finally {
            interact(() -> launcher.gameDir = previousRoot);
        }
    }

    @Test
    void serverParentCreationFailureRestoresControlsBeforeAnyDownload(@TempDir Path root) throws Exception {
        java.io.File previousRoot = launcher.gameDir;
        String previousTarget = launcher.getSelectedVersion();
        AtomicInteger failed = new AtomicInteger();
        Files.writeString(root.resolve("server-downloads"), "not a directory");
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                new ServerInstallWorkflow(launcher).install("1.21.1", LoaderChoice.VANILLA, null, List.of(),
                        new InstanceInstallWorkflow.Listener() {
                            @Override public void onStatus(String message) { }
                            @Override public void onProgress(long downloaded, long total) { }
                            @Override public void onComplete(String directory) { throw new AssertionError("Installation succeeded"); }
                            @Override public void onFailure(String message) { failed.incrementAndGet(); }
                        });
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> failed.get() == 1);
            interact(() -> {
                assertFalse(launcher.versionCombo.isDisabled());
                assertEquals(previousTarget, launcher.getSelectedVersion());
                assertEquals(DownloadTaskCenter.Status.FAILED, launcher.downloadTaskCenter.snapshots().getLast().status());
            });
            assertFalse(Files.exists(root.resolve("versions")));
        } finally {
            interact(() -> launcher.gameDir = previousRoot);
        }
    }

    @Test
    void serverRetryCreatesMissingParentAndKeepsDestinationAndClientTarget(@TempDir Path root) throws Exception {
        java.io.File previousRoot = launcher.gameDir;
        ServerJarDownloader previousDownloader = launcher.serverJarDownloader;
        String previousTarget = launcher.getSelectedVersion();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> destinations = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            interact(() -> {
                launcher.gameDir = root.toFile();
                launcher.serverJarDownloader = new ServerJarDownloader(new VersionManager() {
                    @Override
                    public VersionDownloadTarget resolveDownloadTarget(String version) {
                        assertTrue(Files.isDirectory(root.resolve("server-downloads")));
                        attempts.incrementAndGet();
                        return new VersionDownloadTarget(version, version, "", "");
                    }
                });
                assertFalse(Files.exists(root.resolve("server-downloads")));
                new ServerInstallWorkflow(launcher).install("1.21.1", LoaderChoice.VANILLA, null, List.of(),
                        new InstanceInstallWorkflow.Listener() {
                            @Override public void onStarted() { started.incrementAndGet(); }
                            @Override public void onStatus(String message) {
                                if (message.contains("server-downloads")) destinations.add(message);
                            }
                            @Override public void onProgress(long downloaded, long total) { }
                            @Override public void onComplete(String directory) { throw new AssertionError("Installation succeeded"); }
                            @Override public void onFailure(String message) {
                                failure.set(message);
                                failed.incrementAndGet();
                            }
                        });
            });
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> failed.get() == 1);
            interact(() -> assertNotNull(launcher.downloadTaskCenter.retry(launcher.downloadTaskCenter.snapshots().getLast().id())));
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> failed.get() == 2);
            interact(() -> {
                assertEquals(2, attempts.get(), failure.get());
                assertEquals(2, started.get());
                assertEquals(2, destinations.size());
                assertEquals(destinations.getFirst(), destinations.getLast());
                assertEquals(previousTarget, launcher.getSelectedVersion());
                assertFalse(launcher.versionCombo.isDisabled());
            });
            assertTrue(Files.isDirectory(root.resolve("server-downloads")));
            try (var children = Files.list(root.resolve("server-downloads"))) {
                assertEquals(0, children.count());
            }
            assertFalse(Files.exists(root.resolve("versions")));
        } finally {
            interact(() -> {
                launcher.gameDir = previousRoot;
                launcher.serverJarDownloader = previousDownloader;
            });
        }
    }

    @Test
    void queuedServerInstallCancellationAndRetryInitializeAndRestoreSharedControls() throws Exception {
        DownloadTaskCenter previousCenter = launcher.downloadTaskCenter;
        String previousTarget = launcher.getSelectedVersion();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (DownloadTaskCenter center = blockedCenter(release)) {
            interact(() -> {
                launcher.downloadTaskCenter = center;
                new ServerInstallWorkflow(launcher).install("1.21.1", LoaderChoice.FABRIC, "0.16.9", List.of("0.16.9"),
                        new InstanceInstallWorkflow.Listener() {
                            @Override public void onStarted() { started.incrementAndGet(); }
                            @Override public void onStatus(String message) { }
                            @Override public void onProgress(long downloaded, long total) { }
                            @Override public void onComplete(String directory) { throw new AssertionError("Queued task ran"); }
                            @Override public void onFailure(String message) { failed.incrementAndGet(); }
                        });
                assertTrue(launcher.versionCombo.isDisabled());
                var task = center.snapshots().getLast();
                assertEquals(DownloadTaskCenter.Status.QUEUED, task.status());
                assertTrue(center.cancel(task.id()));
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertEquals(1, started.get());
                assertEquals(1, failed.get());
                assertFalse(launcher.versionCombo.isDisabled());
                var retry = center.retry(center.snapshots().getLast().id());
                assertNotNull(retry);
                assertEquals(2, started.get());
                assertTrue(launcher.versionCombo.isDisabled());
                retry.cancel();
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertEquals(2, failed.get());
                assertFalse(launcher.versionCombo.isDisabled());
                assertEquals(previousTarget, launcher.getSelectedVersion());
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
