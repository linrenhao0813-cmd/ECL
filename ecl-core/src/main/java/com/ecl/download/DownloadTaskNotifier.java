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
    /** Set whenever a change is reported; cleared after a snapshot is actually broadcast. */
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean();

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
            // 节流窗口内只标记脏，待窗口过后再重建快照，避免高频进度通知全量重建。
            dirty.set(true);
            return;
        }
        lastNotifiedAt = now;
        if (!dirty.getAndSet(false) && !immediate) {
            // 自上次广播以来没有任何变化，跳过快照重建与广播。
            return;
        }
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
