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

    SettingsAutoSaveScheduler(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    void enable() {
        enabled = true;
    }

    void markDirty() {
        if (!enabled) {
            return;
        }
        dirty = true;
        ScheduledFuture<?> current = pending;
        if (current != null && !current.isDone()) {
            return;
        }
        pending = executor.schedule(saveAction, AUTO_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    boolean isDirty() {
        return dirty;
    }

    void markClean() {
        dirty = false;
    }

    @Override
    public void close() {
        ScheduledFuture<?> current = pending;
        if (current != null) {
            current.cancel(false);
        }
        executor.shutdownNow();
    }
}
