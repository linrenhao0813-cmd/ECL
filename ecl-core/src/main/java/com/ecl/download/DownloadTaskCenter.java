package com.ecl.download;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A process-wide download job queue.  Download implementations remain responsible
 * for the actual I/O; this class owns ordering, concurrency, cancellation and retry.
 */
public final class DownloadTaskCenter implements AutoCloseable {
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 8;

    /**
     * 保留在任务列表中的最大已结束任务数。排队和进行中的任务不会被丢弃；每次任务
     * 结束时都会裁剪最旧历史，防止长期运行后 entries 与 UI 刷新成本无限增长。
     */
    static final int MAX_RETAINED_FINISHED_TASKS = 200;

    public enum Status {
        QUEUED, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run(TaskContext context) throws Exception;
    }

    @FunctionalInterface
    public interface Listener {
        void onChanged(List<TaskSnapshot> tasks);
    }

    public record TaskSnapshot(
            String id,
            String title,
            String detail,
            Status status,
            double progress,
            long downloadedBytes,
            long totalBytes,
            long speedBytesPerSecond,
            int attempts,
            String errorMessage,
            long createdAtMillis,
            long updatedAtMillis) {
        public TaskSnapshot {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
            status = status == null ? Status.QUEUED : status;
            progress = Math.max(0, Math.min(1, progress));
            downloadedBytes = Math.max(0, downloadedBytes);
            totalBytes = Math.max(0, totalBytes);
            speedBytesPerSecond = Math.max(0, speedBytesPerSecond);
            attempts = Math.max(0, attempts);
            errorMessage = errorMessage == null ? "" : errorMessage;
        }
    }


    private final Object lock = new Object();
    private final LinkedHashMap<String, DownloadTaskEntry<?>> entries = new LinkedHashMap<>();
    private final ArrayDeque<DownloadTaskEntry<?>> queue = new ArrayDeque<>();
    private final DownloadTaskNotifier notifier = new DownloadTaskNotifier(this::snapshots);
    private final DownloadTaskExecutor executor = new DownloadTaskExecutor();
    private final AtomicLong sequence = new AtomicLong();
    private int maxConcurrent;
    private int runningCount;
    private long bandwidthLimitBytesPerSecond;
    private boolean closed;

    public DownloadTaskCenter() {
        this(2, 0);
    }

    public DownloadTaskCenter(int maxConcurrent, long bandwidthLimitBytesPerSecond) {
        this.maxConcurrent = clampConcurrency(maxConcurrent);
        this.bandwidthLimitBytesPerSecond = Math.max(0, bandwidthLimitBytesPerSecond);
    }

    public <T> TaskHandle<T> submit(String title, Operation<T> operation) {
        return submit(title, operation, 0);
    }

    private <T> TaskHandle<T> submit(String title, Operation<T> operation, int previousAttempts) {
        Objects.requireNonNull(operation, "operation");
        DownloadTaskEntry<T> entry;
        synchronized (lock) {
            ensureOpen();
            String id = "download-" + sequence.incrementAndGet();
            entry = new DownloadTaskEntry<>(id, title == null || title.isBlank() ? "下载任务" : title, operation);
            entry.attempts = Math.max(0, previousAttempts);
            entries.put(id, entry);
            queue.addLast(entry);
        }
        // Always notify: when the concurrency limit is reached the new task remains queued and
        // pump() does not emit a second state change for it.
        fireChanged(true);
        pump();
        return new TaskHandle<>(this, entry);
    }

    /**
     * 从最旧的条目开始裁剪已结束历史。活跃任务不计入历史上限，因而不会因队列较长
     * 而被静默丢弃。
     *
     * @return 是否移除了任何条目
     */
    private boolean pruneRetainedLocked() {
        int finished = 0;
        for (DownloadTaskEntry<?> entry : entries.values()) {
            if (DownloadTaskSnapshots.isTerminal(entry.status)) {
                finished++;
            }
        }
        if (finished <= MAX_RETAINED_FINISHED_TASKS) {
            return false;
        }
        boolean pruned = false;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext() && finished > MAX_RETAINED_FINISHED_TASKS) {
            DownloadTaskEntry<?> entry = iterator.next().getValue();
            if (!DownloadTaskSnapshots.isTerminal(entry.status)) {
                continue;
            }
            iterator.remove();
            finished--;
            pruned = true;
        }
        return pruned;
    }

    public List<TaskSnapshot> snapshots() {
        synchronized (lock) {
            List<TaskSnapshot> result = new ArrayList<>(entries.size());
            for (DownloadTaskEntry<?> entry : entries.values()) {
                result.add(DownloadTaskSnapshots.snapshot(entry));
            }
            return Collections.unmodifiableList(result);
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        notifier.add(listener);
    }

    public void removeListener(Listener listener) {
        notifier.remove(listener);
    }

    public int maxConcurrent() {
        synchronized (lock) {
            return maxConcurrent;
        }
    }

    public void setMaxConcurrent(int value) {
        synchronized (lock) {
            maxConcurrent = clampConcurrency(value);
        }
        fireChanged(true);
        pump();
    }

    public long bandwidthLimitBytesPerSecond() {
        synchronized (lock) {
            return bandwidthLimitBytesPerSecond;
        }
    }

    public void setBandwidthLimitBytesPerSecond(long value) {
        long normalized = Math.max(0, value);
        synchronized (lock) {
            bandwidthLimitBytesPerSecond = normalized;
        }
    }

    public boolean cancel(String taskId) {
        DownloadTaskEntry<?> entry;
        Runnable cancellationHook;
        boolean changed;
        synchronized (lock) {
            entry = entries.get(taskId);
            if (entry == null || DownloadTaskSnapshots.isTerminal(entry.status) || entry.status == Status.CANCELLING) {
                return false;
            }
            entry.cancelRequested = true;
            cancellationHook = entry.cancellationHook;
            if (entry.status == Status.QUEUED) {
                queue.remove(entry);
                entry.status = Status.CANCELLED;
                entry.detail = "已取消";
                entry.updatedAtMillis = System.currentTimeMillis();
                entry.completion.cancel(false);
                pruneRetainedLocked();
            } else {
                entry.status = Status.CANCELLING;
                entry.detail = "正在取消";
                entry.updatedAtMillis = System.currentTimeMillis();
            }
            if ((entry.status == Status.RUNNING || entry.status == Status.CANCELLING)
                    && entry.runner != null) {
                entry.runner.interrupt();
            }
            changed = true;
        }
        runCancellation(cancellationHook);
        if (changed) fireChanged(true);
        pump();
        return changed;
    }

    public TaskHandle<?> retry(String taskId) {
        DownloadTaskEntry<?> original;
        synchronized (lock) {
            original = entries.get(taskId);
            if (original == null || !DownloadTaskSnapshots.isTerminal(original.status)
                    || original.status == Status.COMPLETED) {
                return null;
            }
        }
        return submit(original.title, original.operation, original.attempts);
    }

    public int clearFinished() {
        int removed = 0;
        synchronized (lock) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (DownloadTaskSnapshots.isTerminal(iterator.next().getValue().status)) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) fireChanged(true);
        return removed;
    }

    public void cancelAll() {
        List<String> ids;
        synchronized (lock) {
            ids = new ArrayList<>(entries.keySet());
        }
        for (String id : ids) cancel(id);
    }

    private void pump() {
        List<DownloadTaskEntry<?>> toStart = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            while (runningCount < maxConcurrent && !queue.isEmpty()) {
                DownloadTaskEntry<?> entry = queue.removeFirst();
                if (entry.status != Status.QUEUED || entry.cancelRequested) continue;
                entry.status = Status.RUNNING;
                entry.attempts++;
                entry.detail = "正在下载";
                entry.updatedAtMillis = System.currentTimeMillis();
                runningCount++;
                toStart.add(entry);
            }
        }
        if (toStart.isEmpty()) return;
        fireChanged(true);
        for (DownloadTaskEntry<?> entry : toStart) {
            executor.submit(entry, this);
        }
    }

    void finishSuccess(DownloadTaskEntry<?> entry, Object result) {
        boolean changed;
        boolean cancelled = false;
        synchronized (lock) {
            if (entry.status == Status.CANCELLING || entry.cancelRequested) {
                changed = entry.status == Status.RUNNING || entry.status == Status.CANCELLING;
                if (changed) {
                    entry.status = Status.CANCELLED;
                    entry.detail = "已取消";
                    entry.updatedAtMillis = System.currentTimeMillis();
                    runningCount--;
                    cancelled = true;
                }
            } else {
                changed = entry.status == Status.RUNNING;
            }
            if (changed && !cancelled) {
                entry.status = Status.COMPLETED;
                entry.progress = 1;
                entry.detail = "下载完成";
                entry.updatedAtMillis = System.currentTimeMillis();
                runningCount--;
            }
            if (changed) pruneRetainedLocked();
        }
        if (!changed) return;
        if (cancelled) entry.completion.cancel(false);
        else complete(entry, result);
        fireChanged(true);
        pump();
    }

    void finishFailure(DownloadTaskEntry<?> entry, Throwable error) {
        boolean changed;
        boolean cancelled = false;
        synchronized (lock) {
            if (entry.status == Status.CANCELLING || entry.cancelRequested) {
                changed = entry.status == Status.RUNNING || entry.status == Status.CANCELLING;
                if (changed) {
                    entry.status = Status.CANCELLED;
                    entry.detail = "已取消";
                    entry.updatedAtMillis = System.currentTimeMillis();
                    runningCount--;
                    cancelled = true;
                }
            } else {
                changed = entry.status == Status.RUNNING;
            }
            if (changed && !cancelled) {
                entry.status = Status.FAILED;
                entry.errorMessage = DownloadTaskSnapshots.errorMessage(error);
                entry.detail = "下载失败";
                entry.updatedAtMillis = System.currentTimeMillis();
                runningCount--;
            }
            if (changed) pruneRetainedLocked();
        }
        if (!changed) return;
        if (cancelled) entry.completion.cancel(false);
        else entry.completion.completeExceptionally(error);
        fireChanged(true);
        pump();
    }

    void finishCancelled(DownloadTaskEntry<?> entry) {
        boolean changed;
        synchronized (lock) {
            changed = entry.status == Status.RUNNING || entry.status == Status.CANCELLING;
            if (changed) {
                entry.status = Status.CANCELLED;
                entry.detail = "已取消";
                entry.updatedAtMillis = System.currentTimeMillis();
                runningCount--;
                pruneRetainedLocked();
            }
        }
        if (!changed) return;
        entry.completion.cancel(false);
        fireChanged(true);
        pump();
    }

    boolean registerRunner(DownloadTaskEntry<?> entry, Thread runner) {
        synchronized (lock) {
            if (entry.status != Status.RUNNING && entry.status != Status.CANCELLING) {
                return false;
            }
            entry.runner = runner;
            if (entry.cancelRequested) {
                runner.interrupt();
            }
            return true;
        }
    }

    void clearRunner(DownloadTaskEntry<?> entry, Thread runner) {
        synchronized (lock) {
            if (entry.runner == runner) {
                entry.runner = null;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void complete(DownloadTaskEntry<?> entry, Object result) {
        ((CompletableFuture) entry.completion).complete(result);
    }

    private void runCancellation(Runnable cancellationHook) {
        if (cancellationHook == null) return;
        try {
            cancellationHook.run();
        } catch (RuntimeException ignored) {
            // Cancellation must continue even when a downloader's hook is best effort.
        }
    }

    private void fireChanged() {
        notifier.notifyChanged(false);
    }

    private void fireChanged(boolean immediate) {
        notifier.notifyChanged(immediate);
    }

    private static int clampConcurrency(int value) {
        return Math.max(MIN_CONCURRENCY, Math.min(MAX_CONCURRENCY, value));
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("下载任务中心已关闭");
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        cancelAll();
        executor.close();
    }

    public static final class TaskHandle<T> {
        private final DownloadTaskCenter center;
        private final DownloadTaskEntry<T> entry;

        private TaskHandle(DownloadTaskCenter center, DownloadTaskEntry<T> entry) {
            this.center = center;
            this.entry = entry;
        }

        public String id() { return entry.id; }
        public CompletableFuture<T> completion() { return entry.completion; }
        public boolean cancel() { return center.cancel(entry.id); }
        public TaskHandle<?> retry() { return center.retry(entry.id); }
        public TaskSnapshot snapshot() { return center.snapshotFor(entry.id); }
    }

    public static final class TaskContext {
        private final DownloadTaskCenter center;
        private final DownloadTaskEntry<?> entry;

        TaskContext(DownloadTaskCenter center, DownloadTaskEntry<?> entry) {
            this.center = center;
            this.entry = entry;
        }

        public void updateStatus(String detail) {
            synchronized (center.lock) {
                if (entry.status != Status.RUNNING) return;
                entry.detail = detail == null || detail.isBlank() ? "正在下载" : detail;
                entry.updatedAtMillis = System.currentTimeMillis();
            }
            center.fireChanged();
        }

        public void updateProgress(double progress) {
            synchronized (center.lock) {
                if (entry.status != Status.RUNNING) return;
                entry.progress = Math.max(0, Math.min(1, progress));
                entry.updatedAtMillis = System.currentTimeMillis();
            }
            center.fireChanged();
        }

        public void updateProgress(long downloadedBytes, long totalBytes) {
            synchronized (center.lock) {
                if (entry.status != Status.RUNNING) return;
                long now = System.currentTimeMillis();
                long normalizedDownloaded = Math.max(0, downloadedBytes);
                entry.downloadedBytes = normalizedDownloaded;
                entry.totalBytes = Math.max(0, totalBytes);
                entry.progress = entry.totalBytes > 0
                        ? Math.max(0, Math.min(1, (double) normalizedDownloaded / entry.totalBytes))
                        : 0;
                if (entry.progressTimestamp > 0 && now > entry.progressTimestamp
                        && normalizedDownloaded >= entry.progressBytes) {
                    long elapsed = now - entry.progressTimestamp;
                    entry.speedBytesPerSecond = (normalizedDownloaded - entry.progressBytes) * 1000 / elapsed;
                }
                entry.progressTimestamp = now;
                entry.progressBytes = normalizedDownloaded;
                entry.updatedAtMillis = now;
            }
            center.fireChanged();
        }

        public void registerCancellation(Runnable cancellationHook) {
            boolean runNow;
            synchronized (center.lock) {
                runNow = entry.cancelRequested;
                if (!runNow) entry.cancellationHook = cancellationHook;
            }
            if (runNow) center.runCancellation(cancellationHook);
        }

        public boolean isCancelled() {
            return entry.cancelRequested || Thread.currentThread().isInterrupted();
        }
    }

    private TaskSnapshot snapshotFor(String taskId) {
        synchronized (lock) {
            DownloadTaskEntry<?> entry = entries.get(taskId);
            return entry == null ? null : DownloadTaskSnapshots.snapshot(entry);
        }
    }
}
