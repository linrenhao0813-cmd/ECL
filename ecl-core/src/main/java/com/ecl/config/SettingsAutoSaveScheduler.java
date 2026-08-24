package com.ecl.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debounces settings changes and invokes the manager's persistence callback. */
final class SettingsAutoSaveScheduler implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsAutoSaveScheduler.class);
    private static final long AUTO_SAVE_DELAY_MS = 500;

    private final BooleanSupplier saveAction;
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

    SettingsAutoSaveScheduler(BooleanSupplier saveAction) {
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
        boolean saved = false;
        try {
            saved = saveAction.getAsBoolean();
        } catch (Throwable failure) {
            // A failed save must not kill the scheduler or immediately reschedule forever. The
            // dirty state remains set and the next user change can trigger a fresh attempt.
            LOGGER.error("Automatic settings save failed", failure);
        } finally {
            synchronized (this) {
                pending = null;
                if (saved && dirty && enabled && !closed) {
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
