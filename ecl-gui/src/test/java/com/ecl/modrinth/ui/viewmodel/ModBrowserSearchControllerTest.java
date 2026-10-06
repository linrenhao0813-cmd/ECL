package com.ecl.modrinth.ui.viewmodel;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.provider.ModMetadataProvider;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModBrowserSearchControllerTest extends ApplicationTest {
    @Override
    public void start(Stage stage) { }

    @Test
    void aSearchDoesNotCancelOrReplaceTheCurrentInstallation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger searches = new AtomicInteger();
        ModMetadataProvider provider = (ModMetadataProvider) Proxy.newProxyInstance(
                ModMetadataProvider.class.getClassLoader(), new Class<?>[]{ModMetadataProvider.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("search")) {
                        searches.incrementAndGet();
                        return CompletableFuture.completedFuture(new ModSearchResult(List.of(), 0, 20, 0));
                    }
                    throw new AssertionError("Unexpected metadata request: " + method.getName());
                });
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            var installation = center.submit("Mod installation", context -> {
                started.countDown();
                release.await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var loading = new SimpleBooleanProperty();
            var error = new SimpleStringProperty();
            var operation = new SimpleStringProperty();
            var operations = new ModBrowserOperationState(loading, error, operation,
                    new SimpleBooleanProperty(), new SimpleDoubleProperty());
            var controller = new ModBrowserSearchController(provider, new SimpleStringProperty("sodium"),
                    new SimpleObjectProperty<>(ModSearchIndex.RELEVANCE), FXCollections.observableArrayList(),
                    () -> new Instance(), operations, error::set, operation::set, Throwable::getMessage);
            interact(() -> {
                operations.beginDownload("Installing");
                operations.trackDownload(installation);
                operations.track(installation.completion());
                controller.search(false);
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertTrue(loading.get());
                assertTrue(operations.hasActiveDownload());
                assertEquals("Installing", operation.get());
                assertEquals(0, searches.get());
                assertEquals(DownloadTaskCenter.Status.RUNNING, installation.snapshot().status());
                assertFalse(installation.completion().isCancelled());
                operations.finishDownload();
                controller.search(false);
            });
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(1, searches.get());
            release.countDown();
            installation.completion().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    private static final class Instance implements ModInstanceContext {
        @Override public UUID instanceId() { return new UUID(0, 1); }
        @Override public String profileId() { return "search-test"; }
        @Override public String minecraftVersion() { return "1.21.1"; }
        @Override public ModLoader loader() { return ModLoader.FABRIC; }
        @Override public Path gameDirectory() { return Path.of("."); }
        @Override public Path modsDirectory() { return gameDirectory().resolve("mods"); }
    }
}
