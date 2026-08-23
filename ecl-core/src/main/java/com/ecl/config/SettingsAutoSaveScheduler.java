package com.ecl.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Debounces settings changes and invokes the manager's persistence callback. */
final class SettingsAutoSaveScheduler implements AutoCloseable {
    private static final long AUTO_SAVE_DELAY_MS = 500;

    private final Runnable saveAction;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ecl-settings-autosave");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean enabled;
    private volatile boolean dirty;
    private volatile ScheduledFuture<?> pending;
    private long changeVersion;
    private boolean closed;

    SettingsAutoSaveScheduler(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    synchronized void enable() {
        enabled = true;
    }

    synchronized void markDirty() {
        changeVersion++;
        if (!enabled || closed) {
            return;
        }
        dirty = true;
        if (pending != null && !pending.isDone()) {
            return;
        }
        scheduleLocked();
    }

    boolean isDirty() {
        return dirty;
    }

    synchronized long currentVersion() {
        return changeVersion;
    }

    synchronized void markClean(long savedVersion) {
        if (savedVersion == changeVersion) {
            dirty = false;
        }
    }

    private void runSave() {
        try {
            saveAction.run();
        } finally {
            synchronized (this) {
                pending = null;
                if (dirty && enabled && !closed) {
                    scheduleLocked();
                }
            }
        }
    }

    private void scheduleLocked() {
        pending = executor.schedule(this::runSave, AUTO_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        closed = true;
        ScheduledFuture<?> current = pending;
        if (current != null) {
            current.cancel(false);
        }
        executor.shutdownNow();
    }
}
