package com.ecl.ui;

import com.ecl.server.LocalServerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ServerImportJavaTest {
    @TempDir
    Path root;

    @Test
    void importsWithSelectedJavaWhenBundledRuntimeHasNoExecutable() throws Exception {
        Path bundledJava = root.resolve("runtime/bin/java.exe");
        Files.createDirectories(bundledJava.getParent());
        Path systemJava = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path selected = ServerManagementPage.resolveImportJava(bundledJava.toString(), systemJava::toFile);

        Path jar = root.resolve("server.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Server");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.flush();
        }
        LocalServerManager manager = new LocalServerManager(root.resolve("appdata"));
        var profile = manager.importJar("Imported", jar, selected, 2048);
        assertEquals(systemJava.toAbsolutePath().normalize().toString(), profile.javaPath());
        assertTrue(Files.isRegularFile(manager.directory(profile.id()).resolve("server.jar")));
        assertTrue(Files.isRegularFile(jar), "import must preserve the user's original JAR");
    }

    @Test
    void existingJavaDoesNotOpenTheSelectionDialog() {
        Path systemJava = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path detected = ServerManagementPage.resolveImportJava(systemJava.toString(), () -> {
            fail("available Java must not require another choice");
            return null;
        });
        assertEquals(systemJava.toAbsolutePath().normalize(), detected);
    }

    @Test
    void cancellingJavaSelectionLeavesImportCancelled() {
        assertNull(ServerManagementPage.resolveImportJava(root.resolve("missing/java.exe").toString(), () -> null));
    }
}
