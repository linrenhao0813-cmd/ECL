package com.ecl.launch;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A live Minecraft process plus its output plumbing. Holds the OS {@link Process}, an optional
 * {@link ProcessOutputPump} that immediately drains the merged output stream, and a
 * completed future for exit observation.
 */
public final class GameProcess implements AutoCloseable {

    private static final int DEFAULT_OUTPUT_CAPTURE_CHARS = 80_000;
    private static final long GRACEFUL_CLOSE_TIMEOUT_SECONDS = 1;
    private static final long FORCE_CLOSE_TIMEOUT_SECONDS = 1;

    private final Process process;
    private final String versionId;
    private final Path workingDirectory;
    private final AtomicBoolean destroyRequested = new AtomicBoolean();
    private final AtomicBoolean exitMonitorStarted = new AtomicBoolean();

    private volatile ProcessOutputPump outputPump;
    private final CompletableFuture<GameProcess> exitFuture = new CompletableFuture<>();

    /**
     * @param process          freshly started OS process
     * @param versionId        version that was launched
     * @param workingDirectory instance directory, may be null
     */
    public GameProcess(Process process, String versionId, Path workingDirectory) {
        this.process = process;
        this.versionId = versionId;
        this.workingDirectory = workingDirectory;
        outputPump();
    }

    /**
     * Create a process handle and optionally mirror its merged output to an append-only log file.
     * The output pipe remains available to listeners and crash analysis.
     */
    public GameProcess(Process process, String versionId, Path workingDirectory,
                       File processOutputFile) throws IOException {
        this.process = process;
        this.versionId = versionId;
        this.workingDirectory = workingDirectory;
        if (processOutputFile == null) {
            outputPump();
            return;
        }
        OutputStream output = Files.newOutputStream(processOutputFile.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        ProcessOutputPump pump = new ProcessOutputPump(
                process.getInputStream(), DEFAULT_OUTPUT_CAPTURE_CHARS, output);
        outputPump = pump;
        pump.start();
    }

    public String versionId() {
        return versionId;
    }

    /** Instance working directory, or null when unknown. */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /** The undecorated OS process. */
    public Process process() {
        return process;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Return the process exit code, or {@code -1} while it is still running. */
    public int exitCode() {
        return process.isAlive() ? -1 : process.exitValue();
    }

    /** Block until the process exits and return this handle. Throws when the process was destroyed. */
    public GameProcess waitForExit() throws InterruptedException {
        int code = process.waitFor();
        completeExit(code);
        return this;
    }

    /** Wait for at most {@code timeout}; returns whether the process exited in time. */
    public boolean waitForExit(long timeout, TimeUnit unit) throws InterruptedException {
        if (!process.waitFor(timeout, unit)) {
            return false;
        }
        completeExit(process.exitValue());
        return true;
    }

    /**
     * A future completed when the process exits. The first call starts a daemon thread that waits
     * on the process; the underlying process keeps running either way.
     */
    public CompletableFuture<GameProcess> whenExited() {
        if (!exitMonitorStarted.compareAndSet(false, true)) {
            return exitFuture;
        }
        Thread thread = new Thread(() -> {
            try {
                int code = process.waitFor();
                completeExit(code);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exitFuture.cancel(false);
            }
        }, "ecl-wait-game-exit");
        thread.setDaemon(true);
        thread.start();
        return exitFuture;
    }

    private void completeExit(int exitCode) {
        if (!exitFuture.isDone()) {
            exitFuture.complete(this);
        }
    }

    /**
     * Subscribe to decoded output lines. The merged process stream
     * is read by exactly one pump for the lifetime of this process.
     */
    public void attachOutputListener(ProcessOutputListener listener) {
        outputPump().addListener(listener);
    }

    public void detachOutputListener(ProcessOutputListener listener) {
        ProcessOutputPump pump = outputPump;
        if (pump != null) {
            pump.removeListener(listener);
        }
    }

    /** The pump that captures the process output, starting it if needed. */
    public ProcessOutputPump outputPump() {
        ProcessOutputPump pump = outputPump;
        if (pump == null) {
            synchronized (this) {
                pump = outputPump;
                if (pump == null) {
                    pump = new ProcessOutputPump(process.getInputStream(), DEFAULT_OUTPUT_CAPTURE_CHARS);
                    outputPump = pump;
                    pump.start();
                }
            }
        }
        return pump;
    }

    /** The bounded tail of captured output; useful for crash diagnostics. */
    public String capturedOutput() {
        ProcessOutputPump pump = outputPump;
        return pump == null ? "" : pump.capturedText();
    }

    /** Ask the process to terminate gracefully. Idempotent and safe from any thread. */
    public void destroy() {
        if (destroyRequested.compareAndSet(false, true)) {
            process.destroy();
        }
    }

    /** Force the process to terminate. */
    public void destroyForcibly() {
        destroyRequested.set(true);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        destroy();
        try {
            if (!process.waitFor(GRACEFUL_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroyForcibly();
                process.waitFor(FORCE_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            if (!process.isAlive()) {
                completeExit(process.exitValue());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroyForcibly();
        }
        ProcessOutputPump pump = outputPump;
        if (pump != null) {
            pump.close();
        }
    }
}
