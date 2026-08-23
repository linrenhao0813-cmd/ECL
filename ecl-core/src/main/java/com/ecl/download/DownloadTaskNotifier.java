package com.ecl.download;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Broadcasts task snapshots and throttles high-frequency progress-only notifications. */
final class DownloadTaskNotifier {
    private static final long NOTIFY_THROTTLE_MS = 100;
    private final CopyOnWriteArrayList<DownloadTaskCenter.Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final Supplier<List<DownloadTaskCenter.TaskSnapshot>> snapshots;
    private volatile long lastNotifiedAt;

    DownloadTaskNotifier(Supplier<List<DownloadTaskCenter.TaskSnapshot>> snapshots) {
        this.snapshots = snapshots;
    }

    void add(DownloadTaskCenter.Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
        listener.onChanged(snapshots.get());
    }

    void remove(DownloadTaskCenter.Listener listener) {
        listeners.remove(listener);
    }

    void notifyChanged(boolean immediate) {
        if (listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!immediate && now - lastNotifiedAt < NOTIFY_THROTTLE_MS) {
            return;
        }
        lastNotifiedAt = now;
        List<DownloadTaskCenter.TaskSnapshot> current = snapshots.get();
        for (DownloadTaskCenter.Listener listener : listeners) {
            try {
                listener.onChanged(current);
            } catch (RuntimeException ignored) {
                // A UI listener must not stop the download dispatcher.
            }
        }
    }
}
