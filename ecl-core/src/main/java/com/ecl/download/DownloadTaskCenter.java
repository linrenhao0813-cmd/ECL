package com.ecl.download;

import com.ecl.util.HttpUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A process-wide download job queue.  Download implementations remain responsible
 * for the actual I/O; this class owns ordering, concurrency, cancellation and retry.
 */
public final class DownloadTaskCenter implements AutoCloseable {
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 8;

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

    private static final class Entry<T> {
        private final String id;
        private final String title;
        private final Operation<T> operation;
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final long createdAtMillis = System.currentTimeMillis();
        private volatile Thread runner;
        private volatile Runnable cancellationHook;
        private volatile boolean cancelRequested;
        private Status status = Status.QUEUED;
        private String detail = "等待开始";
        private double progress;
        private long downloadedBytes;
        private long totalBytes;
        private long speedBytesPerSecond;
        private long progressTimestamp;
        private long progressBytes;
        private int attempts;
        private String errorMessage = "";
        private long updatedAtMillis = createdAtMillis;

        private Entry(String id, String title, Operation<T> operation) {
            this.id = id;
            this.title = title;
            this.operation = operation;
        }
    }

    private final Object lock = new Object();
    private final LinkedHashMap<String, Entry<?>> entries = new LinkedHashMap<>();
    private final ArrayDeque<Entry<?>> queue = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger threadNumber = new AtomicInteger();
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
        HttpUtil.setDownloadRateLimitBytesPerSecond(this.bandwidthLimitBytesPerSecond);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable,
                    "ecl-download-task-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public <T> TaskHandle<T> submit(String title, Operation<T> operation) {
        return submit(title, operation, 0);
    }

    private <T> TaskHandle<T> submit(String title, Operation<T> operation, int previousAttempts) {
        Objects.requireNonNull(operation, "operation");
        Entry<T> entry;
        synchronized (lock) {
            ensureOpen();
            String id = "download-" + sequence.incrementAndGet();
            entry = new Entry<>(id, title == null || title.isBlank() ? "下载任务" : title, operation);
            entry.attempts = Math.max(0, previousAttempts);
            entries.put(id, entry);
            queue.addLast(entry);
        }
        fireChanged();
        pump();
        return new TaskHandle<>(this, entry);
    }

    public List<TaskSnapshot> snapshots() {
        synchronized (lock) {
            List<TaskSnapshot> result = new ArrayList<>(entries.size());
            for (Entry<?> entry : entries.values()) {
                result.add(snapshot(entry));
            }
            return Collections.unmodifiableList(result);
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onChanged(snapshots());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
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
        fireChanged();
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
        HttpUtil.setDownloadRateLimitBytesPerSecond(normalized);
    }

    public boolean cancel(String taskId) {
        Entry<?> entry;
        Runnable cancellationHook;
        boolean changed;
        synchronized (lock) {
            entry = entries.get(taskId);
            if (entry == null || isTerminal(entry.status) || entry.status == Status.CANCELLING) {
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
            } else {
                entry.status = Status.CANCELLING;
                entry.detail = "正在取消";
                entry.updatedAtMillis = System.currentTimeMillis();
            }
            if (entry.runner != null) entry.runner.interrupt();
            changed = true;
        }
        runCancellation(cancellationHook);
        if (changed) fireChanged();
        pump();
        return changed;
    }

    public TaskHandle<?> retry(String taskId) {
        Entry<?> original;
        synchronized (lock) {
            original = entries.get(taskId);
            if (original == null || !isTerminal(original.status)
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
                if (isTerminal(iterator.next().getValue().status)) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) fireChanged();
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
        List<Entry<?>> toStart = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            while (runningCount < maxConcurrent && !queue.isEmpty()) {
                Entry<?> entry = queue.removeFirst();
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
        fireChanged();
        for (Entry<?> entry : toStart) {
            try {
                executor.submit(() -> execute(entry));
            } catch (RejectedExecutionException error) {
                finishFailure(entry, error);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void execute(Entry<T> entry) {
        entry.runner = Thread.currentThread();
        try {
            if (entry.cancelRequested) throw new CancellationException("已取消");
            T result = entry.operation.run(new TaskContext(this, entry));
            if (entry.cancelRequested || Thread.currentThread().isInterrupted()) {
                finishCancelled(entry);
            } else {
                finishSuccess(entry, result);
            }
        } catch (Throwable error) {
            if (entry.cancelRequested || error instanceof CancellationException
                    || error instanceof InterruptedException) {
                finishCancelled(entry);
            } else {
                finishFailure(entry, error);
            }
        } finally {
            entry.runner = null;
        }
    }

    private void finishSuccess(Entry<?> entry, Object result) {
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
        }
        if (!changed) return;
        if (cancelled) entry.completion.cancel(false);
        else complete(entry, result);
        fireChanged();
        pump();
    }

    private void finishFailure(Entry<?> entry, Throwable error) {
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
                entry.errorMessage = errorMessage(error);
                entry.detail = "下载失败";
                entry.updatedAtMillis = System.currentTimeMillis();
                runningCount--;
            }
        }
        if (!changed) return;
        if (cancelled) entry.completion.cancel(false);
        else entry.completion.completeExceptionally(error);
        fireChanged();
        pump();
    }

    private void finishCancelled(Entry<?> entry) {
        boolean changed;
        synchronized (lock) {
            changed = entry.status == Status.RUNNING || entry.status == Status.CANCELLING;
            if (changed) {
                entry.status = Status.CANCELLED;
                entry.detail = "已取消";
                entry.updatedAtMillis = System.currentTimeMillis();
                runningCount--;
            }
        }
        if (!changed) return;
        entry.completion.cancel(false);
        fireChanged();
        pump();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void complete(Entry<?> entry, Object result) {
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
        List<TaskSnapshot> current = snapshots();
        for (Listener listener : listeners) {
            try {
                listener.onChanged(current);
            } catch (RuntimeException ignored) {
                // A UI listener must not stop the download dispatcher.
            }
        }
    }

    private static TaskSnapshot snapshot(Entry<?> entry) {
        return new TaskSnapshot(entry.id, entry.title, entry.detail, entry.status,
                entry.progress, entry.downloadedBytes, entry.totalBytes,
                entry.speedBytesPerSecond, entry.attempts, entry.errorMessage,
                entry.createdAtMillis, entry.updatedAtMillis);
    }

    private static boolean isTerminal(Status status) {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    private static int clampConcurrency(int value) {
        return Math.max(MIN_CONCURRENCY, Math.min(MAX_CONCURRENCY, value));
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
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
        executor.shutdownNow();
        HttpUtil.setDownloadRateLimitBytesPerSecond(0);
    }

    public static final class TaskHandle<T> {
        private final DownloadTaskCenter center;
        private final Entry<T> entry;

        private TaskHandle(DownloadTaskCenter center, Entry<T> entry) {
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
        private final Entry<?> entry;

        private TaskContext(DownloadTaskCenter center, Entry<?> entry) {
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
            Entry<?> entry = entries.get(taskId);
            return entry == null ? null : snapshot(entry);
        }
    }
}
