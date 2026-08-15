package com.ecl.auth.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthlibInjectorManagerTest {

    @TempDir
    Path directory;

    @Test
    void acceptsOnlyAgentJarWithMatchingChecksum() throws Exception {
        Path agent = directory.resolve("agent.jar");
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class", "example.Agent");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
            // A manifest-only JAR is sufficient to validate the cache format.
        }

        String checksum = AuthlibInjectorManager.sha256Hex(agent);
        assertTrue(AuthlibInjectorManager.isUsableJar(agent, checksum));
        assertFalse(AuthlibInjectorManager.isUsableJar(agent, "0".repeat(64)));
    }

    @Test
    void rejectsZipWithoutJavaAgentManifest() throws Exception {
        Path plainJar = directory.resolve("plain.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(plainJar))) {
            // Valid ZIP/JAR structure, but not executable as a Java agent.
        }

        assertFalse(AuthlibInjectorManager.isUsableJar(
                plainJar, AuthlibInjectorManager.sha256Hex(plainJar)));
    }
}
