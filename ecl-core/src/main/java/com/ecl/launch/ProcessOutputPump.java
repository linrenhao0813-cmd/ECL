package com.ecl.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private final BoundedLogBuffer capture;
    private final List<ProcessOutputListener> listeners = new CopyOnWriteArrayList<>();
    private final Object listenerLock = new Object();
    private final Deque<String> pendingLines = new ArrayDeque<>();
    private final int pendingCapacityChars;
    private int pendingChars;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile Thread runningThread;

    /**
     * @param input            the process pipe to read (stdout or the merged stream)
     * @param captureCapacity  max characters retained for {@link #capturedText()}
     */
    public ProcessOutputPump(InputStream input, int captureCapacity) {
        this.input = input;
        this.capture = new BoundedLogBuffer(captureCapacity);
        this.pendingCapacityChars = captureCapacity;
    }

    /** Subscribe to decoded lines. Safe to call before or after {@link #start()}. */
    public void addListener(ProcessOutputListener listener) {
        synchronized (listenerLock) {
            listeners.add(listener);
            if (listeners.size() == 1 && !pendingLines.isEmpty()) {
                for (String line : pendingLines) {
                    if (!notifyListener(listener, line)) break;
                }
                pendingLines.clear();
                pendingChars = 0;
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
        if (stopped.get()) {
            return;
        }
        Thread thread = runningThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                // Give finite, already-buffered streams a chance to drain before forcing shutdown.
                thread.join(250);
                if (thread.isAlive() && stopped.compareAndSet(false, true)) {
                    input.close();
                    thread.interrupt();
                    thread.join(1_000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stopped.set(true);
                thread.interrupt();
            } catch (IOException ignored) {
                stopped.set(true);
                thread.interrupt();
            }
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
