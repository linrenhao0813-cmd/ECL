package com.ecl.launcher;

import org.junit.jupiter.api.Test;
import com.ecl.util.FileUtil;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class InstallerProcessRunnerTest {
    @TempDir Path root;

    @Test void passesArgumentsExactlyAndUsesPrivateWorkingDirectory() throws Exception {
        Path stage = Files.createDirectory(root.resolve("server with spaces"));
        Path jar = fakeInstaller();
        List<String> args = List.of("--install-dir=" + stage, "space value", "finish");
        new InstallerProcessRunner().run(javaExecutable(), jar, stage, args);
        assertEquals(String.join("\n", args), Files.readString(stage.resolve("received")));
        assertFalse(Files.exists(root.resolve("received")));
    }

    @Test void preservesLiteralQuotesForQuiltJoinAndReparse() throws Exception {
        Path stage = Files.createDirectory(root.resolve("server with spaces"));
        List<String> args = ServerInstaller.serverArguments("1.16.5", ModLoaderInstaller.Loader.QUILT, "0.29.1", stage);
        String officialJar = System.getenv("ECL_TEST_QUILT_INSTALLER");
        if (officialJar != null) {
            // Publisher's Maven SHA-1 for Quilt 0.15.1, checked before loading executable bytecode.
            assertEquals("e4c4e80c91c94d999ffe3468ec2043792b841051", FileUtil.sha1(Path.of(officialJar).toFile()));
            // The previous unquoted argv survives ProcessBuilder, but fails Quilt's actual reparse.
            assertThrows(IOException.class, () -> new InstallerProcessRunner().run(javaExecutable(), fakeInstaller(), stage,
                    List.of("install", "server", "1.16.5", "0.29.1", "--install-dir=" + stage)));
        }
        new InstallerProcessRunner().run(javaExecutable(), fakeInstaller(), stage, args);
        assertEquals(String.join("\n", args), Files.readString(stage.resolve("received")));
        if (officialJar != null) {
            assertEquals(stage.toString(), Files.readString(stage.resolve("quilt-parsed")));
            System.out.println("Quilt 0.15.1 actual CLI parser: unquoted path rejected; quoted argument-file path preserved");
        }
        String joined = String.join(" ", Files.readAllLines(stage.resolve("received")));
        // Quilt CliInstaller.splitQuoted keeps quotes until unqoute; spaces inside them are not separators.
        assertTrue(joined.contains("--install-dir=\"" + stage + "\""));
        try (var paths = Files.list(stage)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".ecl-installer-args-")));
        }
    }

    @Test void cancellationTerminatesInstallerBeforeReturning() throws Exception {
        Path jar = fakeInstaller();
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                new InstallerProcessRunner().run(javaExecutable(), jar, root, List.of("wait"));
            } catch (IOException error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                while (!Files.exists(root.resolve("pid"))) Thread.sleep(20);
            });
            long pid = Long.parseLong(Files.readString(root.resolve("pid")));
            worker.interrupt();
            worker.join(Duration.ofSeconds(15));
            assertFalse(worker.isAlive());
            assertTrue(failure.get() != null);
            assertTrue(interrupted.get());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            worker.interrupt();
            worker.join(Duration.ofSeconds(15));
        }
    }

    @Test void nonzeroExitIsReportedAndPreInterruptedCallerNeverStartsProcess() throws Exception {
        Path jar = fakeInstaller();
        IOException failure = assertThrows(IOException.class,
                () -> new InstallerProcessRunner().run(javaExecutable(), jar, root, List.of("fail")));
        assertTrue(failure.getMessage().contains("7"));
        Files.delete(root.resolve("pid"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class,
                    () -> new InstallerProcessRunner().run(javaExecutable(), jar, root, List.of("finish")));
            assertFalse(Files.exists(root.resolve("pid")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private Path fakeInstaller() throws IOException {
        String className = FakeInstaller.class.getName().replace('.', '/') + ".class";
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeInstaller.class.getName());
        Path jar = root.resolve("fake-installer.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream input = FakeInstaller.class.getClassLoader().getResourceAsStream(className)) {
            output.putNextEntry(new JarEntry(className));
            if (input == null) throw new IOException("Missing test installer bytecode");
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java").toString();
    }

    public static class FakeInstaller {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of("received"), String.join("\n", args));
            Files.writeString(Path.of("pid"), Long.toString(ProcessHandle.current().pid()));
            String officialJar = System.getenv("ECL_TEST_QUILT_INSTALLER");
            if (args[0].equals("install") && officialJar != null) {
                try (var loader = new java.net.URLClassLoader(new java.net.URL[]{Path.of(officialJar).toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
                    Class<?> cli = loader.loadClass("org.quiltmc.installer.CliInstaller");
                    var parse = cli.getDeclaredMethod("parse", String.class);
                    parse.setAccessible(true);
                    // Exactly CliInstaller.run's join step. Never invoke Action.run or start a server.
                    Object action = parse.invoke(null, String.join(" ", args));
                    var directory = action.getClass().getDeclaredField("installDir");
                    directory.setAccessible(true);
                    Files.writeString(Path.of("quilt-parsed"), (String) directory.get(action));
                }
            }
            if (args[0].equals("wait")) Thread.sleep(60_000);
            if (args[0].equals("fail")) System.exit(7);
        }
    }
}
