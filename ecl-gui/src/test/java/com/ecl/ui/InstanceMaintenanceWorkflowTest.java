package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.download.DownloadTaskCenter;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceMaintenanceWorkflowTest extends ApplicationTest {
    private LauncherUI launcher;

    @Override
    public void start(Stage stage) {
        launcher = new LauncherUI();
        launcher.start(new Stage());
    }

    @Override
    public void stop() {
        launcher.stop();
        launcher.primaryStage.close();
    }

    @Test
    void reportFormattingUsesLocaleIndependentKeysAndClosingDoesNotOpenAnotherModal() throws Exception {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            var report = new com.ecl.download.GameInstallationVerifier.Report("locale", java.util.List.of(
                    new com.ecl.download.GameInstallationVerifier.Check(
                            com.ecl.download.GameInstallationVerifier.Category.LIBRARY, "library.jar",
                            com.ecl.download.GameInstallationVerifier.Status.MISSING, "", false)), "fingerprint");
            Class<?> inspectionType = java.util.Arrays.stream(InstanceCheckRepairWorkflow.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("Inspection")).findFirst().orElseThrow();
            var constructor = inspectionType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            var root = Path.of(".").toAbsolutePath();
            Object inspection = constructor.newInstance("locale", report, null, "", null, "", root, root, root, root);
            var workflow = new InstanceCheckRepairWorkflow(launcher);
            var format = InstanceCheckRepairWorkflow.class.getDeclaredMethod("formatReport", inspectionType);
            format.setAccessible(true);
            String text = (String) format.invoke(workflow, inspection);
            assertTrue(text.contains(com.ecl.util.Messages.get("instanceRepair.category.library")));
            assertTrue(text.contains(com.ecl.util.Messages.get("instanceRepair.status.missing")));
            var show = InstanceCheckRepairWorkflow.class.getDeclaredMethod("showReport", inspectionType, Runnable.class);
            show.setAccessible(true);
            AtomicInteger refreshed = new AtomicInteger();
            interact(() -> {
                Platform.runLater(() -> Window.getWindows().stream()
                        .filter(window -> window != launcher.primaryStage && window.isShowing())
                        .toList().forEach(Window::hide));
                try {
                    show.invoke(workflow, inspection, (Runnable) refreshed::incrementAndGet);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                assertEquals(1, refreshed.get());
                assertTrue(Window.getWindows().stream().noneMatch(window -> window != launcher.primaryStage && window.isShowing()));
            });
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void rejectedRepairInspectionClearsBusyStateAndRefreshes() throws Exception {
        verifyCompletion(false, true);
    }

    @Test
    void rejectedRecoveryInspectionClearsBusyStateAndRefreshes() throws Exception {
        verifyCompletion(true, true);
    }

    @Test
    void queuedRepairCancellationClearsBusyStateAndRefreshes() throws Exception {
        verifyCompletion(false, false);
    }

    @Test
    void queuedRecoveryCancellationClearsBusyStateAndRefreshes() throws Exception {
        verifyCompletion(true, false);
    }

    private void verifyCompletion(boolean recovery, boolean rejected) throws Exception {
        String id = "workflow-completion-test";
        Path metadata = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{\"id\":\"workflow-completion-test\",\"eclMinecraftVersion\":\"1.21.1\",\"libraries\":[]}");
        DownloadTaskCenter previousCenter = launcher.downloadTaskCenter;
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshed = new AtomicInteger();
        InstanceCheckRepairWorkflow repair = new InstanceCheckRepairWorkflow(launcher);
        InstanceUpgradeRecoveryWorkflow restore = new InstanceUpgradeRecoveryWorkflow(launcher);
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            if (rejected) {
                center.close();
            } else {
                CountDownLatch started = new CountDownLatch(1);
                center.submit("blocker", context -> {
                    started.countDown();
                    release.await();
                    return null;
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
            }
            interact(() -> {
                launcher.downloadTaskCenter = center;
                Runnable finished = () -> {
                    assertFalse(repair.isBusy(id));
                    assertFalse(restore.isBusy());
                    refreshed.incrementAndGet();
                    Platform.runLater(() -> Window.getWindows().stream()
                            .filter(window -> window != launcher.primaryStage).toList().forEach(Window::hide));
                };
                if (recovery) restore.open(id, finished);
                else repair.start(id, finished);
                if (!rejected) {
                    assertTrue(recovery ? restore.isBusy() : repair.isBusy(id));
                    assertTrue(center.cancel(center.snapshots().getLast().id()));
                }
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshed.get() == 1);
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertFalse(repair.isBusy(id));
                assertFalse(restore.isBusy());
                assertEquals(1, refreshed.get());
            });
        } finally {
            release.countDown();
            interact(() -> launcher.downloadTaskCenter = previousCenter);
            Files.deleteIfExists(metadata);
        }
    }
}
