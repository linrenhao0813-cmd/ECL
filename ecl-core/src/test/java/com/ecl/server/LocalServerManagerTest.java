package com.ecl.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalServerManagerTest {
    @TempDir
    Path temporary;

    @Test
    void importsIsolatedCopyAndPersistsSettingsWithExplicitEulaGate() throws Exception {
        LocalServerManager manager = manager();
        assertEquals(List.of(), manager.list());
        Path source = fixtureJar();
        LocalServerProfile imported = manager.importJar("My server", source, javaExecutable(), 512);
        LocalServerProfile second = manager.importJar("Another server", source, javaExecutable(), 256);
        assertNotEquals(imported.id(), second.id());
        assertFalse(imported.eulaAccepted());
        assertThrows(IOException.class, () -> manager.start(imported.id()));
        assertTrue(Files.readString(manager.directory(imported.id()).resolve("eula.txt")).contains("false"));
        assertEquals(Files.size(source), Files.size(manager.directory(imported.id()).resolve("server.jar")));
        Files.delete(source);
        LocalServerProfile updated = manager.saveSettings(imported.id(), "Renamed", javaExecutable(), 768);
        assertEquals(768, updated.memoryMb());
        manager.setEulaAccepted(imported.id(), true);
        LocalServerProfile reloaded = manager().list().stream().filter(profile -> profile.id().equals(imported.id())).findFirst().orElseThrow();
        assertEquals("Renamed", reloaded.name());
        assertEquals(768, reloaded.memoryMb());
        assertTrue(reloaded.eulaAccepted());
        Files.writeString(manager.directory(imported.id()).resolve("eula.txt"), "eula=false\n");
        assertThrows(IOException.class, () -> manager.start(imported.id()));
        manager.setEulaAccepted(imported.id(), false);
        assertThrows(IOException.class, () -> manager.start(imported.id()));
    }

    @Test
    void realChildJvmReceivesConsoleAndGracefulStopAcrossManagerInstances() throws Exception {
        LocalServerManager manager = manager();
        LocalServerProfile profile = manager.importJar("Lifecycle", fixtureJar(), javaExecutable(), 512);
        String id = profile.id();
        Path directory = manager.directory(id);
        manager.setEulaAccepted(id, true);
        try {
            manager.start(id);
            await(() -> manager.readLogTail(id, 10).stream().anyMatch(line -> line.equals("READY nogui")));
            LocalServerManager reopened = manager();
            assertTrue(reopened.isRunning(id));
            assertTrue(reopened.canControl(id));
            assertThrows(IOException.class, () -> reopened.start(id));
            assertThrows(IOException.class, () -> reopened.saveSettings(id, "Changed", javaExecutable(), 1024));
            assertThrows(IOException.class, () -> reopened.setEulaAccepted(id, false));
            Path marker = directory.resolve("process.json");
            String identity = Files.readString(marker);
            Files.delete(marker);
            assertTrue(reopened.isRunning(id));
            assertThrows(IOException.class, () -> reopened.start(id));
            assertThrows(IOException.class, () -> reopened.saveSettings(id, "Changed", javaExecutable(), 1024));
            Files.writeString(marker, identity);
            reopened.sendCommand(id, "say hello");
            await(() -> Files.exists(directory.resolve("last-command.txt"))
                    && Files.readString(directory.resolve("last-command.txt")).equals("say hello"));
            reopened.stop(id);
            await(() -> !reopened.isRunning(id));
            assertEquals("saved", Files.readString(directory.resolve("world-saved.txt")));
            assertFalse(reopened.canControl(id));
            assertThrows(IOException.class, () -> reopened.sendCommand(id, "list"));
            assertTrue(reopened.readLogTail(id, 10).contains("COMMAND stop"));
            reopened.start(id);
            assertTrue(reopened.isRunning(id));
            reopened.stop(id);
            await(() -> !reopened.isRunning(id));
        } finally {
            if (manager.isRunning(id) && manager.canControl(id)) {
                manager.stop(id);
                await(() -> !manager.isRunning(id));
            }
        }
    }

    @Test
    void persistedIdentityDoesNotConfuseReusedPidAndCorruptRecordsFailClosed() throws Exception {
        LocalServerManager manager = manager();
        String id = manager.importJar("Identity", fixtureJar(), javaExecutable(), 256).id();
        Path marker = manager.directory(id).resolve("process.json");
        ProcessHandle current = ProcessHandle.current();
        long started = current.info().startInstant().orElseThrow().toEpochMilli();
        Files.writeString(marker, "{\"pid\":" + current.pid() + ",\"startedAtEpochMillis\":" + started + "}");
        assertTrue(manager().isRunning(id));
        assertFalse(manager().canControl(id));
        assertThrows(IOException.class, () -> manager().sendCommand(id, "stop"));
        assertThrows(IOException.class, () -> manager().saveSettings(id, "Mutated", javaExecutable(), 256));
        Files.writeString(marker, "{\"pid\":" + current.pid() + ",\"startedAtEpochMillis\":" + (started - 1) + "}");
        assertFalse(manager().isRunning(id));
        Files.writeString(marker, "{\"pid\":0,\"startedAtEpochMillis\":0}");
        assertThrows(IOException.class, () -> manager().isRunning(id));
        Files.writeString(marker, "invalid JSON");
        assertThrows(IOException.class, () -> manager().isRunning(id));
        Files.writeString(marker, " ".repeat(17000));
        assertThrows(IOException.class, () -> manager().isRunning(id));
    }

    @Test
    void boundsLogTailAndRejectsMultilineCommandsAndUnsafeIds() throws Exception {
        LocalServerManager manager = manager();
        String id = manager.importJar("Logs", fixtureJar(), javaExecutable(), 256).id();
        assertEquals(List.of(), manager.readLogTail(id, 20));
        Path log = manager.directory(id).resolve("console.log");
        Files.writeString(log, "old\n".repeat(100000) + "penultimate\nfinal\n", StandardCharsets.UTF_8);
        assertEquals(List.of("penultimate", "final"), manager.readLogTail(id, 2));
        assertTrue(manager.readLogTail(id, 2000).size() <= 2000);
        assertThrows(IllegalArgumentException.class, () -> manager.readLogTail(id, 0));
        assertThrows(IllegalArgumentException.class, () -> manager.readLogTail(id, 2001));
        for (String command : List.of("", "list\nstop", "list\rstop", "say\u0000oops", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException.class, () -> manager.sendCommand(id, command));
        }
        assertThrows(IllegalArgumentException.class, () -> manager.directory("../outside"));
        assertThrows(IllegalArgumentException.class, () -> manager.directory(null));
        assertThrows(IOException.class, () -> manager.start("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    @Test
    void rejectsInvalidJarJavaSettingsAndDamagedProfile() throws Exception {
        LocalServerManager manager = manager();
        Path badJar = temporary.resolve("bad.jar");
        Files.writeString(badJar, "not a jar");
        assertThrows(IOException.class, () -> manager.importJar("Bad", badJar, javaExecutable(), 256));
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(badJar))) {
            archive.putNextEntry(new JarEntry("empty.txt"));
            archive.closeEntry();
        }
        assertThrows(IOException.class, () -> manager.importJar("Bad", badJar, javaExecutable(), 256));
        Path valid = fixtureJar();
        assertThrows(IOException.class, () -> manager.importJar("Bad", valid, temporary.resolve("missing.exe"), 256));
        assertThrows(IllegalArgumentException.class, () -> manager.importJar("", valid, javaExecutable(), 256));
        assertThrows(IllegalArgumentException.class, () -> manager.importJar("Bad", valid, javaExecutable(), 255));
        String id = manager.importJar("Valid", valid, javaExecutable(), 256).id();
        Path profile = manager.directory(id).resolve("profile.json");
        Files.writeString(profile, "null");
        assertThrows(IOException.class, manager::list);
        Files.writeString(profile, "{\"id\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"name\":\"Other\","
                + "\"javaPath\":\"" + javaExecutable().toString().replace("\\", "\\\\") + "\",\"memoryMb\":256}");
        assertThrows(IOException.class, manager::list);
    }

    @Test
    void rejectsSymlinkSourcesAndManagedPathRedirects() throws Exception {
        Path source = fixtureJar();
        Path sourceLink = temporary.resolve("source-link.jar");
        try {
            Files.createSymbolicLink(sourceLink, source);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getMessage());
        }
        LocalServerManager manager = manager();
        assertThrows(IOException.class, () -> manager.importJar("Link", sourceLink, javaExecutable(), 256));
        String id = manager.importJar("Safe", source, javaExecutable(), 256).id();
        Path directory = manager.directory(id);
        Files.createSymbolicLink(directory.resolve("console.log"), temporary.resolve("outside.log"));
        assertThrows(IOException.class, () -> manager.readLogTail(id, 10));
        Files.createSymbolicLink(directory.resolve("process.json"), temporary.resolve("outside.json"));
        assertThrows(IOException.class, () -> manager.isRunning(id));
    }

    private LocalServerManager manager() {
        return new LocalServerManager(temporary.resolve("data"));
    }

    private static Path javaExecutable() {
        String file = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", file);
    }

    private Path fixtureJar() throws IOException {
        Path jar = temporary.resolve("fixture.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServerMain.class.getName());
        String className = FakeServerMain.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
                var input = FakeServerMain.class.getClassLoader().getResourceAsStream(className)) {
            if (input == null) throw new IOException("Missing child JVM fixture class");
            output.putNextEntry(new JarEntry(className));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static void await(IoCondition condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            while (!condition.test()) Thread.sleep(25);
        });
    }

    @FunctionalInterface
    private interface IoCondition {
        boolean test() throws IOException;
    }
}
