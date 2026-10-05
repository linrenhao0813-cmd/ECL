package com.ecl.ui;

import com.ecl.server.LocalServerManager;
import com.ecl.server.LocalServerProfile;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerManagementPageTest extends ApplicationTest {
    private LauncherUI ui;
    private ServerManagementPage page;
    private LocalServerManager manager;
    private LocalServerProfile profile;
    private Path data;

    @Override
    public void start(Stage stage) throws Exception {
        data = Files.createTempDirectory("ecl-server-ui-test-");
        Path jar = data.resolve("server.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Server");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.flush();
        }
        manager = new LocalServerManager(data);
        profile = manager.importJar("Test server", jar,
                Path.of(System.getProperty("java.home"), "bin", "java.exe"), 2048);
        ui = new LauncherUI();
        ui.primaryStage = stage;
        ui.controller = new MainController();
        page = new ServerManagementPage(ui, manager);
        stage.setScene(new Scene(page, 1100, 800));
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        if (page != null) page.close();
        if (ui != null && ui.controller != null) ui.controller.close();
        if (data != null) {
            try (var paths = Files.walk(data)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void eulaRequiresAnExplicitSavedChoiceAndUnsavedSettingsBlockStart() throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() ->
                !page.lookup("#local-server-eula").isDisabled()).get());
        assertFalse(manager.list().getFirst().eulaAccepted());
        interact(() -> {
            CheckBox agreement = (CheckBox) page.lookup("#local-server-eula");
            Button start = (Button) page.lookup("#local-server-start");
            assertFalse(agreement.isSelected());
            assertTrue(start.isDisabled());
            agreement.fire();
            assertTrue(start.isDisabled(), "unsaved EULA choice must not enable start");
        });
        assertFalse(manager.list().getFirst().eulaAccepted());
        interact(() -> ((Button) page.lookup("#local-server-save")).fire());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() ->
                !page.lookup("#local-server-start").isDisabled()).get());
        assertTrue(manager.list().getFirst().eulaAccepted());
        assertTrue(Files.readString(manager.directory(profile.id()).resolve("eula.txt")).contains("eula=true"));
        interact(() -> {
            ((TextField) page.lookup("#local-server-memory")).setText("4096");
            assertTrue(page.lookup("#local-server-start").isDisabled(), "start must use explicitly saved settings");
            page.setCompact(true);
            page.setCompact(false);
            page.close();
        });
        assertFalse(manager.isRunning(profile.id()), "accepting EULA and saving settings must never launch a server");
    }
}
