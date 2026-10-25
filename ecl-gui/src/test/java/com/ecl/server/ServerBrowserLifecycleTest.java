package com.ecl.server;

import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBrowserLifecycleTest extends ApplicationTest {
    private final List<String> messages = new ArrayList<>();
    private ServerBrowserView view;

    @Override
    public void start(Stage stage) {
        view = new ServerBrowserView(messages::add, ignored -> { });
    }

    @Override
    public void stop() {
        if (view != null) view.close();
    }

    @Test
    void queuedDirectoryCompletionDoesNotPublishAfterClose() {
        interact(() -> {
            completeDirectoryFailure();
            view.close();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(messages.isEmpty(), "a detached page must not overwrite the current status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualRefreshReprobesKnownStatusesEvenWhenDirectoryFails() throws Exception {
        var probes = new QueuedExecutor();
        var directory = new QueuedExecutor();
        Object probeController = field(view, "statusProbeController");
        Object directoryController = field(view, "directoryRefreshController");
        replaceExecutor(probeController, probes);
        replaceExecutor(directoryController, directory);
        Map<String, ServerStatus> statuses = (Map<String, ServerStatus>) field(view, "statusMap");
        for (PublicServer server : ServerCatalog.load().servers()) {
            statuses.put(server.address(), ServerStatus.online(10, 100, "test"));
        }
        String snapshot = System.getProperty("ecl.snapshot");
        try {
            System.setProperty("ecl.snapshot", "false");
            interact(() -> {
                try {
                    ((Button) field(view, "refreshStatusButton")).fire();
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                completeDirectoryFailure();
            });
            WaitForAsyncUtils.waitForFxEvents();
            assertFalse(probes.tasks.isEmpty(), "refresh must enqueue fresh status probes despite old known statuses");
            assertTrue(messages.stream().anyMatch(message -> !message.isBlank()));
        } finally {
            if (snapshot == null) System.clearProperty("ecl.snapshot");
            else System.setProperty("ecl.snapshot", snapshot);
        }
    }

    private void completeDirectoryFailure() {
        try {
            Method completed = ServerBrowserView.class.getDeclaredMethod("finishDirectoryRefresh",
                    ServerDirectoryService.DirectorySnapshot.class, Throwable.class);
            completed.setAccessible(true);
            completed.invoke(view, null, new IllegalStateException("directory unavailable"));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void replaceExecutor(Object controller, QueuedExecutor replacement) throws Exception {
        Field executor = controller.getClass().getDeclaredField("executor");
        executor.setAccessible(true);
        ((java.util.concurrent.ExecutorService) executor.get(controller)).shutdownNow();
        executor.set(controller, replacement);
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
