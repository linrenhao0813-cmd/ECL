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
