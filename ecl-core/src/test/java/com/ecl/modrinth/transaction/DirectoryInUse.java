package com.ecl.modrinth.transaction;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Test helper that keeps a directory in use by a child process whose working directory is that
 * directory. Windows then refuses to delete the directory, which reproduces the post-commit
 * cleanup failure a real open file handle causes without weakening the production code.
 */
public final class DirectoryInUse implements AutoCloseable {
    private static final long READY_TIMEOUT_MILLIS = 15_000;
    private static final long EXIT_TIMEOUT_SECONDS = 15;
    private static final long POLL_MILLIS = 20;

    private final Process process;
    private final Path marker;

    private DirectoryInUse(Process process, Path marker) {
        this.process = process;
        this.marker = marker;
    }

    public static DirectoryInUse acquire(Path directory) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        Path marker = target.resolveSibling(target.getFileName() + ".in-use");
        Files.deleteIfExists(marker);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path classes = Path.of(URI.create(DirectoryInUse.class.getProtectionDomain()
                .getCodeSource().getLocation().toExternalForm()));
        Process process = new ProcessBuilder(java.toString(), "-cp", classes.toString(),
                DirectoryInUse.class.getName(), marker.toString())
                .directory(target.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            awaitReady(process, marker);
            return new DirectoryInUse(process, marker);
        } catch (IOException | RuntimeException failure) {
            try {
                new DirectoryInUse(process, marker).close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Files.writeString(Path.of(args[0]), "ready");
        Thread.sleep(TimeUnit.SECONDS.toMillis(60));
    }

    private static void awaitReady(Process process, Path marker) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Directory holder exited before signalling readiness");
            }
            if (Files.exists(marker)) {
                return;
            }
            sleep();
        }
        throw new IOException("Directory holder did not become ready: " + marker);
    }

    private static void sleep() throws IOException {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for directory holder", interrupted);
        }
    }

    @Override
    public void close() throws IOException {
        process.destroyForcibly();
        boolean exited;
        try {
            exited = process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping directory holder", interrupted);
        }
        Files.deleteIfExists(marker);
        if (!exited) {
            throw new IOException("Directory holder did not stop: " + marker);
        }
    }
}
