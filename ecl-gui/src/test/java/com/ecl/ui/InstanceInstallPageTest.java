package com.ecl.ui;

import com.ecl.modrinth.model.ContentVersion;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceInstallPageTest extends ApplicationTest {
    private final List<CompletableFuture<List<String>>> requests = new ArrayList<>();
    private final List<CompletableFuture<List<ContentVersion>>> apiRequests = new ArrayList<>();
    private static final ContentVersion OLDER_API = new ContentVersion("older-api", "", "0.100.0", "release");
    private static final ContentVersion NEWER_API = new ContentVersion("newer-api", "", "0.101.0", "release");
    private InstanceInstallPage page;
    private ComboBox<String> versions;
    private ComboBox<ContentVersion> apiVersions;
    private Button install;

    @Override
    @SuppressWarnings("unchecked")
    public void start(Stage stage) {
        page = new InstanceInstallPage(new LauncherUI(), "1.21.1", () -> { }, choice -> {
            CompletableFuture<List<String>> request = new CompletableFuture<>();
            requests.add(request);
            return request;
        }, () -> {
            CompletableFuture<List<ContentVersion>> request = new CompletableFuture<>();
            apiRequests.add(request);
            return request;
        });
        stage.setScene(new Scene(page, 1000, 900));
        stage.show();
        versions = (ComboBox<String>) page.lookup("#instance-loader-version");
        apiVersions = (ComboBox<ContentVersion>) page.lookup("#instance-fabric-api-version");
        install = (Button) page.lookup("#instance-install-action");
    }

    @Test
    void everyLoaderRequiresAnExplicitVersionWhileVanillaNeedsNone() {
        interact(() -> assertFalse(install.isDisabled()));
        for (LoaderChoice choice : List.of(LoaderChoice.FABRIC, LoaderChoice.QUILT,
                LoaderChoice.FORGE, LoaderChoice.NEOFORGE)) {
            interact(() -> {
                choose(choice);
                assertTrue(install.isDisabled());
                assertTrue(versions.isDisabled());
                assertNull(versions.getValue());
                requests.getLast().complete(List.of("newer", "older"));
                if (choice == LoaderChoice.FABRIC) apiRequests.getLast().complete(List.of(NEWER_API, OLDER_API));
            });
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> {
                assertEquals(List.of("newer", "older"), versions.getItems());
                assertNull(versions.getValue());
                assertTrue(install.isDisabled());
                versions.setValue("older");
                if (choice == LoaderChoice.FABRIC) {
                    assertTrue(install.isDisabled());
                    assertNull(apiVersions.getValue());
                    apiVersions.setValue(OLDER_API);
                }
                assertFalse(install.isDisabled());
            });
        }
        interact(() -> {
            choose(LoaderChoice.VANILLA);
            assertFalse(install.isDisabled());
            assertFalse(versions.getParent().getParent().isManaged());
        });
    }

    @Test
    void ignoresOldResponsesEvenWhenSwitchingBackToTheSameLoader() {
        interact(() -> {
            choose(LoaderChoice.FABRIC);
            choose(LoaderChoice.FORGE);
            choose(LoaderChoice.FABRIC);
            requests.get(2).complete(List.of("current"));
            apiRequests.get(1).complete(List.of(OLDER_API));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            versions.setValue("current");
            apiVersions.setValue(OLDER_API);
            requests.get(0).complete(List.of("stale"));
            requests.get(1).completeExceptionally(new IOException("stale failure"));
            apiRequests.get(0).complete(List.of(NEWER_API));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertEquals(List.of("current"), versions.getItems());
            assertEquals("current", versions.getValue());
            assertEquals(List.of(OLDER_API), apiVersions.getItems());
            assertEquals(OLDER_API, apiVersions.getValue());
            assertFalse(install.isDisabled());
        });
    }

    @Test
    void failureAndEmptyListsBlockInstallAndCanBeReloaded() {
        interact(() -> {
            choose(LoaderChoice.QUILT);
            requests.getLast().completeExceptionally(new IOException("offline"));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertTrue(install.isDisabled());
            refresh().fire();
            requests.getLast().complete(List.of());
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertTrue(install.isDisabled());
            assertTrue(versions.isDisabled());
            refresh().fire();
            requests.getLast().complete(List.of("available"));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            versions.setValue("available");
            assertFalse(install.isDisabled());
        });
    }

    @Test
    void workflowRejectsMissingVersionBeforeStartingAnyDownload() {
        List<String> failures = new ArrayList<>();
        InstanceInstallWorkflow.Listener listener = new InstanceInstallWorkflow.Listener() {
            @Override
            public void onStatus(String message) { }
            @Override
            public void onProgress(long downloaded, long total) { }
            @Override
            public void onComplete(String profileId) { }
            @Override
            public void onFailure(String message) { failures.add(message); }
        };
        interact(() -> {
            InstanceInstallWorkflow workflow = new InstanceInstallWorkflow(new LauncherUI());
            workflow.install("1.21.1", LoaderChoice.FABRIC, null, null, listener);
            workflow.install("1.21.1", LoaderChoice.FORGE, " ", null, listener);
            workflow.install("1.21.1", LoaderChoice.FABRIC, "0.16.9", null, listener);
            assertEquals(3, failures.size());
        });
    }

    @Test
    void apiFailureAndEmptyListRequireRetryAndAnExplicitSelection() {
        interact(() -> {
            choose(LoaderChoice.FABRIC);
            requests.getLast().complete(List.of("0.16.9"));
            apiRequests.getLast().completeExceptionally(new IOException("API offline"));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            versions.setValue("0.16.9");
            assertTrue(install.isDisabled());
            apiRefresh().fire();
            apiRequests.getLast().complete(List.of());
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertTrue(apiVersions.isDisabled());
            assertTrue(install.isDisabled());
            apiRefresh().fire();
            apiRequests.getLast().complete(List.of(NEWER_API, OLDER_API));
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertNull(apiVersions.getValue());
            assertTrue(install.isDisabled());
            apiVersions.setValue(OLDER_API);
            assertFalse(install.isDisabled());
            apiRefresh().fire();
            assertNull(apiVersions.getValue());
            assertTrue(install.isDisabled());
            choose(LoaderChoice.VANILLA);
            assertFalse(apiVersions.getParent().getParent().isManaged());
            assertFalse(install.isDisabled());
        });
    }

    private void choose(LoaderChoice choice) {
        page.lookupAll(".instance-install-choice").stream()
                .map(ToggleButton.class::cast)
                .filter(button -> button.getUserData() == choice)
                .findFirst().orElseThrow().fire();
    }

    private Button refresh() {
        return (Button) versions.getParent().getChildrenUnmodifiable().get(1);
    }

    private Button apiRefresh() {
        return (Button) apiVersions.getParent().getChildrenUnmodifiable().get(1);
    }
}
