package com.ecl.modrinth.ui.viewmodel;

import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.service.ModManagementService;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledModControllerTest extends ApplicationTest {
    @Override
    public void start(Stage stage) { }

    @Test
    void managementCompletionUpdatesListButFailurePreservesItAndReleasesBusyState() {
        var enabled = new CompletableFuture<List<InstalledMod>>();
        var uninstalled = new CompletableFuture<List<InstalledMod>>();
        ModManagementService service = (ModManagementService) Proxy.newProxyInstance(
                ModManagementService.class.getClassLoader(), new Class<?>[]{ModManagementService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setEnabled" -> enabled;
                    case "uninstall" -> uninstalled;
                    default -> throw new AssertionError("Unexpected operation: " + method.getName());
                });
        var original = new InstalledMod(new UUID(0, 1), "project", "v1", "slug", "Mod", "1", "mod.jar",
                Path.of("mods/mod.jar"), "", "", 1, "1.21.1", "fabric", "release", true, false, "",
                Instant.EPOCH, Instant.EPOCH);
        var installed = FXCollections.observableArrayList(original);
        var loading = new SimpleBooleanProperty();
        var error = new SimpleStringProperty();
        var operation = new SimpleStringProperty();
        var operations = new ModBrowserOperationState(loading, error, operation,
                new SimpleBooleanProperty(), new SimpleDoubleProperty());
        var controller = new InstalledModController(service, null, () -> null, installed,
                operations, Throwable::getMessage, error::set, operation::set);

        interact(() -> controller.setEnabled(List.of("project"), false));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> assertTrue(loading.get()));
        enabled.complete(List.of());
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertTrue(installed.isEmpty());
            assertFalse(loading.get());
            assertEquals("", error.get());
            installed.add(original);
            controller.uninstall(List.of("project"));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> assertTrue(loading.get()));
        uninstalled.completeExceptionally(new IllegalStateException("Cannot remove a required dependency"));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertEquals(List.of(original), installed);
            assertEquals("Cannot remove a required dependency", error.get());
            assertFalse(loading.get());
        });
    }
}
