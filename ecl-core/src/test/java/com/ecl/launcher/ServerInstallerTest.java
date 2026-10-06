package com.ecl.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ServerInstallerTest {
    @TempDir Path root;

    private final FakeBackend backend = new FakeBackend();

    @Test void vanillaPublishesWithoutStartingOrAcceptingEula() throws Exception {
        Path target = root.resolve("server with spaces");
        assertEquals(target, new ServerInstaller(backend).install("1.21.1", null, null, target, null));
        assertEquals("eula=false\n", Files.readString(target.resolve("eula.txt")));
        assertTrue(Files.readString(target.resolve("start.bat")).contains("-jar \"server.jar\" nogui"));
        assertTrue(Files.readString(target.resolve("start.sh")).contains("exec '"));
        assertFalse(backend.ran);
        try (var paths = Files.list(root)) { assertEquals(1, paths.count()); }
    }

    @Test void allLoaderCommandsAndLayoutsAreDedicatedServerOnly() throws Exception {
        for (ModLoaderInstaller.Loader loader : ModLoaderInstaller.Loader.values()) {
            backend.loader = loader;
            Path target = root.resolve(loader.id());
            new ServerInstaller(backend).install("1.21.1", loader, "1.2.3", target, null);
            List<String> args = backend.arguments;
            assertFalse(args.stream().anyMatch(s -> s.toLowerCase().contains("client")));
            switch (loader) {
                case FABRIC -> assertEquals(List.of("server", "-mcversion", "1.21.1", "-loader", "1.2.3", "-dir"), args.subList(0, 6));
                case QUILT -> {
                    assertEquals(List.of("install", "server", "1.21.1", "1.2.3"), args.subList(0, 4));
                    assertTrue(args.get(4).startsWith("--install-dir="));
                    assertTrue(args.get(4).startsWith("--install-dir=\""));
                    assertTrue(args.get(4).endsWith("\""));
                    assertFalse(args.contains("--download-server"));
                }
                case FORGE -> assertEquals("--installServer", args.getFirst());
                case NEOFORGE -> assertEquals("--install-server", args.getFirst());
            }
            assertTrue(Files.isDirectory(target.resolve("libraries")));
            assertTrue(Files.readString(target.resolve("start.bat")).contains("C:\\Java Space\\java.exe"));
            assertFalse(Files.exists(target.resolve(".installer.jar")));
        }
    }

    @Test void quiltInstallerUsesNewerJavaWithoutChangingOlderServerScripts() throws Exception {
        backend.loader = ModLoaderInstaller.Loader.QUILT;
        backend.javaPath = "C:/Java8/bin/java.exe";
        backend.installerJavaPath = "C:/Java17/bin/java.exe";
        Path target = root.resolve("old quilt server");
        new ServerInstaller(backend).install("1.16.5", backend.loader, "1.2.3", target, null);
        assertEquals(backend.installerJavaPath, backend.executedJava);
        assertTrue(Files.readString(target.resolve("start.bat")).contains(backend.javaPath));
        assertFalse(Files.readString(target.resolve("start.bat")).contains(backend.installerJavaPath));
        assertEquals(17, ServerInstaller.installerJavaRequirement(ModLoaderInstaller.Loader.QUILT, 8));
        assertEquals(17, ServerInstaller.installerJavaRequirement(ModLoaderInstaller.Loader.QUILT, 16));
        assertEquals(21, ServerInstaller.installerJavaRequirement(ModLoaderInstaller.Loader.QUILT, 21));
        assertEquals(8, ServerInstaller.installerJavaRequirement(ModLoaderInstaller.Loader.FABRIC, 8));
        assertEquals(8, ServerInstaller.installerJavaRequirement(ModLoaderInstaller.Loader.FORGE, 8));
    }

    @Test void refusesExistingTargetsAndInvalidVersionsBeforeDownloads() throws Exception {
        Path target = Files.createDirectory(root.resolve("existing"));
        Files.writeString(target.resolve("world"), "keep");
        ServerInstaller installer = new ServerInstaller(backend);
        assertThrows(IOException.class, () -> installer.install("1.21.1", null, null, target, null));
        assertEquals("keep", Files.readString(target.resolve("world")));
        for (String version : List.of("../escape", "a/b", "--option", "a?b")) {
            assertThrows(IOException.class, () -> installer.install(version, null, null, root.resolve("new"), null));
        }
        assertFalse(backend.downloaded);
    }

    @Test void failureAndInterruptedCancellationCleanOnlyStaging() throws Exception {
        backend.fail = true;
        Path target = root.resolve("failed");
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install("1.21.1", null, null, target, null));
        assertFalse(Files.exists(target));
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
        backend.fail = false;
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> new ServerInstaller(backend).install("1.21.1", null, null, target, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(target));
    }

    @Test void cancellationAfterInstallerAndMissingOutputsNeverPublish() throws Exception {
        backend.loader = ModLoaderInstaller.Loader.FABRIC;
        backend.interrupt = true;
        try {
            assertThrows(IOException.class, () -> new ServerInstaller(backend).install("1.21.1", backend.loader, "1.2.3", root.resolve("cancel"), null));
        } finally { Thread.interrupted(); }
        backend.interrupt = false;
        backend.missing = true;
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install("1.21.1", backend.loader, "1.2.3", root.resolve("missing"), null));
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
    }

    @Test void rejectsLinksInInstallerOutputWithoutDeletingTheirTargets() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("world"), "keep");
        Path probe = root.resolve("probe");
        createDirectoryLink(probe, outside);
        Files.delete(probe);
        backend.link = outside;
        backend.loader = ModLoaderInstaller.Loader.FABRIC;
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install(
                "1.21.1", backend.loader, "1.2.3", root.resolve("unsafe"), null));
        assertEquals("keep", Files.readString(outside.resolve("world")));
        assertFalse(Files.exists(root.resolve("unsafe")));
        try (var paths = Files.list(root)) { assertEquals(1, paths.count()); }
    }

    @Test void linkedAncestorIsRejectedBeforeDownloads() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = root.resolve("link");
        createDirectoryLink(link, outside);
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install(
                "1.21.1", null, null, link.resolve("server"), null));
        assertFalse(backend.downloaded);
        assertFalse(Files.exists(outside.resolve("server")));
    }

    @Test void preservesConcurrentTarget() throws Exception {
        Path target = root.resolve("raced");
        backend.raceTarget = target;
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install("1.21.1", null, null, target, null));
        assertEquals("keep", Files.readString(target.resolve("world")));
        try (var paths = Files.list(root)) { assertEquals(1, paths.count()); }
    }

    @Test void javaPathsAreQuotedAndUnsafeBatchExpansionsRejected() throws Exception {
        backend.javaPath = "C:/Java Space/O'Brien/java.exe";
        Path target = root.resolve("quoted");
        new ServerInstaller(backend).install("1.21.1", null, null, target, null);
        assertTrue(Files.readString(target.resolve("start.bat")).contains("\"C:/Java Space/O'Brien/java.exe\""));
        assertTrue(Files.readString(target.resolve("start.sh")).contains("O'\"'\"'Brien"));
        for (String unsafe : List.of("C:/%PATH%/java.exe", "C:/!evil!/java.exe", "java\ncommand", "a\"b")) {
            backend.javaPath = unsafe;
            assertThrows(IOException.class, () -> new ServerInstaller(backend).install(
                    "1.21.1", null, null, root.resolve("badjava"), null));
        }
        assertFalse(Files.exists(root.resolve("badjava")));
    }

    @Test void atomicPublicationNeverReplacesEvenEmptyDirectories() throws Exception {
        Path stage = Files.createDirectory(root.resolve("stage"));
        Files.writeString(stage.resolve("file"), "content");
        Path target = Files.createDirectory(root.resolve("target"));
        assertThrows(IOException.class, () -> ServerInstaller.publish(stage, target));
        assertTrue(Files.isDirectory(target));
        assertEquals("content", Files.readString(stage.resolve("file")));
    }

    @Test void legacyForgeJarLayoutIsSupportedAndOfficialFilesPreserved() throws Exception {
        backend.loader = ModLoaderInstaller.Loader.FORGE;
        backend.legacy = true;
        Path target = root.resolve("legacy");
        new ServerInstaller(backend).install("1.12.2", backend.loader, "1.2.3", target, null);
        assertTrue(Files.readString(target.resolve("start.bat")).contains("-jar \"forge-1.12.2-1.2.3-universal.jar\" nogui"));
        assertEquals("official", Files.readString(target.resolve("run.bat")));
    }

    @Test void corruptInstallerNeverExecutesAndUnavailableLoaderFailsBeforeDownload() throws Exception {
        backend.loader = ModLoaderInstaller.Loader.FABRIC;
        backend.corrupt = true;
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install(
                "1.21.1", backend.loader, "1.2.3", root.resolve("corrupt"), null));
        assertFalse(backend.ran);
        assertFalse(Files.exists(root.resolve("corrupt")));
        backend.downloaded = false;
        assertThrows(IOException.class, () -> new ServerInstaller(backend).install(
                "1.21.1", backend.loader, "9.9.9", root.resolve("unavailable"), null));
        assertFalse(backend.downloaded);
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true).start();
                String output = new String(junction.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assumeTrue(junction.waitFor() == 0, "Junction unavailable: " + output);
            } else {
                assumeTrue(false, "Symbolic links unavailable: " + unavailable);
            }
        }
    }

    private static final class FakeBackend implements ServerInstaller.Backend {
        boolean downloaded, ran, fail, interrupt, missing, legacy, corrupt;
        String javaPath = "C:\\Java Space\\java.exe";
        String installerJavaPath, executedJava;
        Path link, raceTarget;
        ModLoaderInstaller.Loader loader;
        List<String> arguments;
        public List<String> versions(String mc, ModLoaderInstaller.Loader type) { return List.of("1.2.3"); }
        public String java(String mc, ModLoaderInstaller.Loader type) { return javaPath; }
        public String installerJava(ModLoaderInstaller.Loader type, String serverJava) {
            return installerJavaPath == null ? serverJava : installerJavaPath;
        }
        public void vanilla(String mc, Path target, ModLoaderInstaller.Listener listener) throws IOException {
            downloaded = true;
            Files.writeString(target, "verified server");
            if (fail) throw new IOException("offline failure");
            if (raceTarget != null) {
                Files.createDirectory(raceTarget);
                Files.writeString(raceTarget.resolve("world"), "keep");
            }
        }
        public void installer(String mc, ModLoaderInstaller.Loader type, String version, Path target,
                              ModLoaderInstaller.Listener listener) throws IOException {
            Files.writeString(target, "verified installer");
            if (corrupt) new LoaderArtifactVerifier().verify(target, "SHA-1", "0".repeat(40));
        }
        public void run(String java, Path installer, Path stage, List<String> args) throws IOException {
            ran = true;
            executedJava = java;
            arguments = args;
            if (interrupt) Thread.currentThread().interrupt();
            if (missing) return;
            if (link != null) {
                try {
                    createDirectoryLink(stage.resolve("libraries"), link);
                } catch (Exception error) {
                    throw new IOException(error);
                }
                return;
            }
            Files.createDirectories(stage.resolve("libraries"));
            if (loader == ModLoaderInstaller.Loader.FABRIC || loader == ModLoaderInstaller.Loader.QUILT) {
                Files.writeString(stage.resolve(loader.id() + "-server-launch.jar"), "launcher");
            } else {
                Path lib = Files.createDirectories(stage.resolve("libraries/net/test"));
                if (legacy) {
                    Files.writeString(stage.resolve("forge-1.12.2-1.2.3-universal.jar"), "launcher");
                } else {
                    Files.writeString(lib.resolve("win_args.txt"), "arguments");
                    Files.writeString(lib.resolve("unix_args.txt"), "arguments");
                }
                Files.writeString(stage.resolve("run.bat"), "official");
                Files.writeString(stage.resolve("run.sh"), "official");
            }
        }
    }
}
