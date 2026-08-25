package com.ecl.download;

import com.ecl.util.ThreadFactories;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Runs queued download operations and routes their terminal outcome to the task center. */
final class DownloadTaskExecutor implements AutoCloseable {
    private static final int MAX_DOWNLOAD_TASK_THREADS = 8;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            MAX_DOWNLOAD_TASK_THREADS,
            ThreadFactories.daemon("ecl-download-task"));

    void submit(DownloadTaskEntry<?> entry, DownloadTaskCenter center) {
        try {
            executor.submit(() -> executeUnchecked(entry, center));
        } catch (RejectedExecutionException error) {
            center.finishFailure(entry, error);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeUnchecked(DownloadTaskEntry<?> entry, DownloadTaskCenter center) {
        execute((DownloadTaskEntry<Object>) entry, center);
    }

    private <T> void execute(DownloadTaskEntry<T> entry, DownloadTaskCenter center) {
        Thread runner = Thread.currentThread();
        if (!center.registerRunner(entry, runner)) {
            return;
        }
        try {
            if (entry.cancelRequested) {
                throw new CancellationException("已取消");
            }
            T result = entry.operation.run(new DownloadTaskCenter.TaskContext(center, entry));
            if (entry.cancelRequested || Thread.currentThread().isInterrupted()) {
                center.finishCancelled(entry);
            } else {
                center.finishSuccess(entry, result);
            }
        } catch (Throwable error) {
            if (entry.cancelRequested || error instanceof CancellationException
                    || error instanceof InterruptedException) {
                center.finishCancelled(entry);
            } else {
                center.finishFailure(entry, error);
            }
        } finally {
            center.clearRunner(entry, runner);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
