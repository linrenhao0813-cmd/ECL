package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.service.InstanceUpdateService;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceUpdateWorkflowTest extends ApplicationTest {
    private LauncherUI ui;

    @Override
    public void start(Stage stage) {
        ui = new LauncherUI();
        ui.start(new Stage());
    }

    @Override
    public void stop() {
        ui.stop();
        ui.primaryStage.close();
    }

    @Test
    void synchronousPreviewPrepareAndExecuteRejectionsRestoreControls(@TempDir Path root) throws Exception {
        String id = "workflow-reject-" + UUID.randomUUID();
        Path metadata = profile(root, id);
        var previousRoot = ui.gameDir;
        Field executorField = field(InstanceUpdateService.class, "executor");
        Object service = ui.controller.instanceUpdateService();
        Object previousExecutor = executorField.get(service);
        executorField.set(service, (Executor) command -> { throw new RejectedExecutionException("closed executor"); });
        try {
            interact(() -> {
                ui.gameDir = root.toFile();
                ui.instanceUpdates.startFor(id);
                assertIdle();
                var preview = preview(root, id);
                arm();
                invoke("prepareExecution", new Class<?>[] {long.class, InstanceUpdateService.Preview.class, Set.class},
                        1L, preview, Set.of());
                assertIdle();
                arm();
                invoke("preparePackExecution", new Class<?>[] {long.class, InstanceUpdateService.Preview.class}, 1L, preview);
                assertIdle();
                arm();
                invoke("execute", new Class<?>[] {long.class, InstanceUpdateService.PreparedExecution.class},
                        1L, new InstanceUpdateService.PreparedExecution(preview, List.of()));
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(this::assertIdle);
        } finally {
            executorField.set(service, previousExecutor);
            ui.gameDir = previousRoot;
            Files.deleteIfExists(metadata);
            Files.deleteIfExists(metadata.getParent());
        }
    }

    @Test
    void cancelledPreviewCannotClearTheNextGeneration(@TempDir Path root) throws Exception {
        String id = "workflow-generation-" + UUID.randomUUID();
        Path metadata = profile(root, id);
        var previousRoot = ui.gameDir;
        Field executorField = field(InstanceUpdateService.class, "executor");
        Object service = ui.controller.instanceUpdateService();
        Object previousExecutor = executorField.get(service);
        List<Runnable> queued = new ArrayList<>();
        executorField.set(service, (Executor) queued::add);
        try {
            interact(() -> {
                ui.gameDir = root.toFile();
                ui.instanceUpdates.startFor(id);
                invoke("cancelPreflight", new Class<?>[0]);
                assertIdle();
                ui.instanceUpdates.startFor(id);
                assertTrue(isUpdating());
            });
            Files.delete(metadata);
            queued.getFirst().run();
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> assertTrue(isUpdating(), "late first completion must not clear the second task"));
            queued.get(1).run();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() -> !isUpdating()).get());
            interact(this::assertIdle);
        } finally {
            executorField.set(service, previousExecutor);
            ui.gameDir = previousRoot;
            Files.deleteIfExists(metadata);
            Files.deleteIfExists(metadata.getParent());
        }
    }

    @Test
    void cancellingFinalConfirmationRestoresTheWorkflow(@TempDir Path root) {
        interact(() -> {
            arm();
            Platform.runLater(() -> javafx.stage.Window.getWindows().stream()
                    .filter(window -> window != ui.primaryStage && window.isShowing())
                    .toList().forEach(javafx.stage.Window::hide));
            invoke("confirmExecution", new Class<?>[] {long.class, InstanceUpdateService.PreparedExecution.class},
                    1L, new InstanceUpdateService.PreparedExecution(preview(root, "confirmation"), List.of()));
            assertIdle();
        });
    }

    private static Path profile(Path root, String id) throws Exception {
        Files.createDirectories(root.resolve("versions").resolve(id));
        Path metadata = ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{\"id\":\"" + id + "\",\"type\":\"release\","
                + "\"mainClass\":\"net.minecraft.client.main.Main\",\"eclMinecraftVersion\":\"1.21.1\"}");
        return metadata;
    }

    private static InstanceUpdateService.Preview preview(Path root, String id) {
        ModInstanceContext instance = new TestInstance(UUID.randomUUID(), id, root);
        return new InstanceUpdateService.Preview(instance, Instant.now(), root, root, "fingerprint",
                new LoaderUpdateService.UpdatePlan(id, "1.21.1", "Fabric", "1", "2", true),
                List.of(), List.of(), Set.of(), List.of(), null, null);
    }

    private void arm() {
        try {
            field(InstanceUpdateWorkflow.class, "updating").set(ui.instanceUpdates, true);
            ((AtomicLong) field(InstanceUpdateWorkflow.class, "generation").get(ui.instanceUpdates)).set(1);
            ui.setControlsBusy(true);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void assertIdle() {
        assertFalse(isUpdating());
        assertFalse(ui.versionCombo.isDisabled());
    }

    private boolean isUpdating() {
        try {
            return field(InstanceUpdateWorkflow.class, "updating").getBoolean(ui.instanceUpdates);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void invoke(String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = InstanceUpdateWorkflow.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(ui.instanceUpdates, arguments);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record TestInstance(UUID instanceId, String profileId, Path gameDirectory) implements ModInstanceContext {
        @Override public String minecraftVersion() { return "1.21.1"; }
        @Override public ModLoader loader() { return ModLoader.FABRIC; }
        @Override public Path modsDirectory() { return gameDirectory.resolve("mods"); }
    }
}
