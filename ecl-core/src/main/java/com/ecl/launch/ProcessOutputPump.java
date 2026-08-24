package com.ecl.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads lines from a game process pipe on a daemon thread, forwarding each decoded line to
 * subscribers and keeping a bounded tail capture for crash analysis. Latched, so
 * {@link #start()} is safe to call from more than one thread.
 */
public final class ProcessOutputPump implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessOutputPump.class);

    private final InputStream input;
    private final OutputStream mirror;
    private final BoundedLogBuffer capture;
    private final List<ProcessOutputListener> listeners = new CopyOnWriteArrayList<>();
    private final Object listenerLock = new Object();
    private final Deque<String> pendingLines = new ArrayDeque<>();
    private final int pendingCapacityChars;
    private int pendingChars;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean mirrorFailed = new AtomicBoolean();
    private volatile Thread runningThread;

    /**
     * @param input            the process pipe to read (stdout or the merged stream)
     * @param captureCapacity  max characters retained for {@link #capturedText()}
     */
    public ProcessOutputPump(InputStream input, int captureCapacity) {
        this(input, captureCapacity, null);
    }

    /**
     * Create a pump that also mirrors each decoded output line to {@code mirror}. The process pipe
     * remains owned by this pump, so listeners and the bounded capture continue to see output.
     */
    public ProcessOutputPump(InputStream input, int captureCapacity, OutputStream mirror) {
        this.input = input;
        this.mirror = mirror;
        this.capture = new BoundedLogBuffer(captureCapacity);
        this.pendingCapacityChars = captureCapacity;
    }

    /** Subscribe to decoded lines. Safe to call before or after {@link #start()}. */
    public void addListener(ProcessOutputListener listener) {
        synchronized (listenerLock) {
            listeners.add(listener);
            if (listeners.size() == 1 && !pendingLines.isEmpty()) {
                while (!pendingLines.isEmpty()) {
                    String line = pendingLines.peekFirst();
                    if (!notifyListener(listener, line)) {
                        break;
                    }
                    pendingChars -= pendingLines.removeFirst().length() + 1;
                }
            }
        }
    }

    public void removeListener(ProcessOutputListener listener) {
        synchronized (listenerLock) {
            listeners.remove(listener);
        }
    }

    /** Start pumping on a daemon thread; a no-op once already started. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::run, "ecl-process-output");
        thread.setDaemon(true);
        runningThread = thread;
        thread.start();
    }

    /** The bounded tail of everything captured so far; empty when the pump never ran. */
    public String capturedText() {
        return capture.toString();
    }

    /** Stop pumping. Idempotent; interrupts the reader so {@link #start()}-ed threads wind down. */
    @Override
    public void close() {
        Thread thread = runningThread;
        if (thread == Thread.currentThread()) {
            stopped.set(true);
            closeInput();
            return;
        }
        if (thread != null && thread.isAlive()) {
            try {
                // Give finite, already-buffered streams a chance to drain before forcing shutdown.
                thread.join(250);
                if (thread.isAlive()) {
                    stopped.set(true);
                    closeInput();
                    thread.interrupt();
                    thread.join(1_000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stopped.set(true);
                closeInput();
                if (thread != null) {
                    thread.interrupt();
                }
            }
        } else {
            stopped.set(true);
            closeInput();
        }
        stopped.set(true);
    }

    private void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (!stopped.get() && (line = reader.readLine()) != null) {
                if (stopped.get()) {
                    break;
                }
                capture.appendLine(line);
                mirrorLine(line);
                synchronized (listenerLock) {
                    if (listeners.isEmpty()) {
                        addPendingLine(line);
                    } else {
                        for (ProcessOutputListener listener : listeners) {
                            notifyListener(listener, line);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // The process ended or its pipe broke; nothing meaningful left to read.
        } finally {
            closeMirror();
        }
    }

    private void mirrorLine(String line) {
        if (mirror == null || mirrorFailed.get()) {
            return;
        }
        try {
            mirror.write(line.getBytes(StandardCharsets.UTF_8));
            mirror.write('\n');
        } catch (IOException error) {
            if (mirrorFailed.compareAndSet(false, true)) {
                LOGGER.warn("Unable to mirror process output to the configured log file", error);
            }
        }
    }

    private void closeInput() {
        try {
            input.close();
        } catch (IOException ignored) {
            // The reader is already being stopped; there is no useful recovery here.
        }
    }

    private void closeMirror() {
        if (mirror == null) {
            return;
        }
        try {
            mirror.close();
        } catch (IOException error) {
            LOGGER.debug("Unable to close process output log", error);
        }
    }

    private void addPendingLine(String line) {
        pendingLines.addLast(line);
        pendingChars += line.length() + 1;
        while (pendingChars > pendingCapacityChars && !pendingLines.isEmpty()) {
            pendingChars -= pendingLines.removeFirst().length() + 1;
        }
    }

    private boolean notifyListener(ProcessOutputListener listener, String line) {
        try {
            listener.onOutputLine(line);
            return true;
        } catch (Throwable error) {
            LOGGER.warn("Process output listener failed; removing it", error);
            listeners.remove(listener);
            return false;
        }
    }
}
