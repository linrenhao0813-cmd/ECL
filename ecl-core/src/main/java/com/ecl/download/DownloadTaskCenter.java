package com.ecl.download;

import com.ecl.ECLConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * A process-wide download job queue.  Download implementations remain responsible
 * for the actual I/O; this class owns ordering, concurrency, cancellation and retry.
 */
public final class DownloadTaskCenter implements AutoCloseable {
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

    /** Creates a fresh operation instance for each attempt, including retries. */
    @FunctionalInterface
    public interface OperationFactory<T> {
        Operation<T> create();
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
    private boolean closed;

    public DownloadTaskCenter() {
        this(2);
    }

    public DownloadTaskCenter(int maxConcurrent) {
        this.maxConcurrent = ECLConfig.clampDownloadConcurrency(maxConcurrent);
    }

    public <T> TaskHandle<T> submit(String title, Operation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return submit(title, () -> operation);
    }

    /** Submit a task whose operation is recreated for every retry attempt. */
    public <T> TaskHandle<T> submit(String title, OperationFactory<T> operationFactory) {
        return submit(title, operationFactory, null);
    }

    /** Completion is reported for every attempt, including queued cancellation and retries. */
    public <T> TaskHandle<T> submit(String title, OperationFactory<T> operationFactory,
                                    BiConsumer<T, Throwable> onComplete) {
        Objects.requireNonNull(operationFactory, "operationFactory");
        DownloadTaskEntry<T> entry;
        synchronized (lock) {
            entry = enqueueLocked(title, operationFactory, 0, new AtomicReference<>(), onComplete);
        }
        return startSubmitted(entry);
    }

    private <T> DownloadTaskEntry<T> enqueueLocked(String title, OperationFactory<T> operationFactory,
                                                   int previousAttempts,
                                                   AtomicReference<DownloadTaskEntry<?>> family,
                                                   BiConsumer<T, Throwable> onComplete) {
        ensureOpen();
        Operation<T> operation = Objects.requireNonNull(operationFactory.create(), "operationFactory.create()");
        String id = "download-" + sequence.incrementAndGet();
        DownloadTaskEntry<T> entry = new DownloadTaskEntry<>(id,
                title == null || title.isBlank() ? "下载任务" : title, operation, family);
        entry.operationFactory = operationFactory;
        entry.completionHandler = onComplete;
        entry.attempts = Math.max(0, previousAttempts);
        entries.put(id, entry);
        family.set(entry);
        queue.addLast(entry);
        return entry;
    }

    private <T> TaskHandle<T> startSubmitted(DownloadTaskEntry<T> entry) {
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
            maxConcurrent = ECLConfig.clampDownloadConcurrency(value);
        }
        fireChanged(true);
        pump();
    }

    public boolean cancel(String taskId) {
        DownloadTaskEntry<?> entry;
        Runnable cancellationHook;
        boolean queuedCancellation;
        synchronized (lock) {
            DownloadTaskEntry<?> requested = entries.get(taskId);
            entry = requested == null ? null : requested.familyCurrent.get();
            if (entry == null || DownloadTaskSnapshots.isTerminal(entry.status)
                    || entry.status == Status.CANCELLING) {
                return false;
            }
            entry.cancelRequested = true;
            cancellationHook = entry.cancellationHook;
            queuedCancellation = entry.status == Status.QUEUED;
            if (queuedCancellation) {
                queue.remove(entry);
                entry.status = Status.CANCELLED;
                entry.detail = "已取消";
                entry.updatedAtMillis = System.currentTimeMillis();
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
        }
        if (queuedCancellation) {
            notifyCompletion(entry, null, new CancellationException("已取消"));
            entry.completion.cancel(false);
        }
        runCancellation(cancellationHook);
        fireChanged(true);
        pump();
        return true;
    }

    public TaskHandle<?> retry(String taskId) {
        DownloadTaskEntry<?> retry;
        synchronized (lock) {
            DownloadTaskEntry<?> original = entries.get(taskId);
            if (!canRetry(original)) {
                return null;
            }
            retry = enqueueRetryLocked(original);
        }
        return startSubmitted(retry);
    }

    private <T> DownloadTaskEntry<T> enqueueRetryLocked(DownloadTaskEntry<T> original) {
        return enqueueLocked(original.title, original.operationFactory, original.attempts,
                original.familyCurrent, original.completionHandler);
    }

    public boolean canRetry(String taskId) {
        synchronized (lock) {
            return canRetry(entries.get(taskId));
        }
    }

    /** Called while holding lock so the task and its retry family are read together. */
    private boolean canRetry(DownloadTaskEntry<?> entry) {
        return entry != null && entry.familyCurrent.get() == entry
                && entry.completion.isDone()
                && (entry.status == Status.FAILED || entry.status == Status.CANCELLED);
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
        finish(entry, Status.COMPLETED, result, null);
    }

    void finishFailure(DownloadTaskEntry<?> entry, Throwable error) {
        finish(entry, Status.FAILED, null, error);
    }

    void finishCancelled(DownloadTaskEntry<?> entry) {
        finish(entry, Status.CANCELLED, null, null);
    }

    private void finish(DownloadTaskEntry<?> entry, Status outcome, Object result, Throwable error) {
        Status terminalStatus;
        synchronized (lock) {
            if (entry.status != Status.RUNNING && entry.status != Status.CANCELLING) {
                return;
            }
            // Cancellation wins even if the operation returns or fails before it notices the request.
            terminalStatus = entry.cancelRequested || entry.status == Status.CANCELLING
                    ? Status.CANCELLED : outcome;
            entry.status = terminalStatus;
            entry.detail = switch (terminalStatus) {
                case COMPLETED -> "下载完成";
                case FAILED -> "下载失败";
                case CANCELLED -> "已取消";
                default -> throw new IllegalArgumentException("Expected a terminal download status");
            };
            if (terminalStatus == Status.COMPLETED) {
                entry.progress = 1;
            } else if (terminalStatus == Status.FAILED) {
                entry.errorMessage = DownloadTaskSnapshots.errorMessage(error);
            }
            entry.updatedAtMillis = System.currentTimeMillis();
            runningCount--;
            pruneRetainedLocked();
        }
        // Completing a future may run user callbacks; keep them outside the queue lock.
        notifyCompletion(entry, result,
                terminalStatus == Status.CANCELLED ? new CancellationException("已取消") : error);
        switch (terminalStatus) {
            case CANCELLED -> entry.completion.cancel(false);
            case COMPLETED -> complete(entry, result);
            case FAILED -> entry.completion.completeExceptionally(error);
            default -> throw new IllegalStateException("Unexpected download outcome");
        }
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

    @SuppressWarnings("unchecked")
    private static <T> void notifyCompletion(DownloadTaskEntry<T> entry, Object result, Throwable error) {
        if (entry.completionHandler == null) return;
        try {
            entry.completionHandler.accept((T) result, error);
        } catch (RuntimeException ignored) {
            // A UI completion callback must not stop the dispatcher or leave its future unfinished.
        }
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

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("下载调度器已关闭");
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
