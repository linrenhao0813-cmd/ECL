package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Executes verified file downloads and aggregates progress for one download phase. */
final class GameDownloadBatchExecutor {
    private static final long MAX_UNSIZED_LIBRARY_BYTES = 1024L * 1024 * 1024;
    private final ExecutorService executor;

    GameDownloadBatchExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    void download(List<DownloadTask> tasks, String phase,
                  GameDownloader.DownloadListener listener) throws IOException {
        if (tasks.isEmpty()) {
            if (listener != null) {
                listener.onStatus(phase + "已是最新，无需下载");
            }
            return;
        }

        int threadCount = Math.min(ECLConfig.DOWNLOAD_THREADS, tasks.size());
        if (listener != null) {
            listener.onStatus("使用 " + threadCount + " 个线程下载" + phase
                    + "，共 " + tasks.size() + " 个文件...");
        }
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        List<Future<Void>> phaseTasks = new ArrayList<>(tasks.size());
        for (DownloadTask task : tasks) {
            phaseTasks.add(completionService.submit(() -> {
                try {
                    downloadAndVerify(task, listener);
                    return null;
                } finally {
                    // Download cancellation may set the worker's interrupt flag. Clear it before
                    // the shared executor reuses this thread for an unrelated task.
                    Thread.interrupted();
                }
            }));
        }

        int completed = 0;
        try {
            while (completed < tasks.size()) {
                try {
                    completionService.take().get();
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    IOException failure = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException(cause == null ? error : cause);
                    phaseTasks.forEach(task -> task.cancel(true));
                    throw new IOException(phase + "下载失败: " + failure.getMessage(), failure);
                }
                completed++;
                if (listener != null && (completed == tasks.size() || completed % 25 == 0)) {
                    listener.onStatus("下载" + phase + ": " + completed + "/" + tasks.size());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(phase + "下载被中断", error);
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                phaseTasks.forEach(task -> task.cancel(true));
            }
        }

    }

    private void downloadAndVerify(DownloadTask task,
                                   GameDownloader.DownloadListener listener) throws IOException {
        NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                URI.create(task.url()), task.sourceLabel() + " URL");
        long maxBytes = task.expectedSize() > 0
                ? task.expectedSize() : MAX_UNSIZED_LIBRARY_BYTES;
        HttpUtil.downloadFileWithProgress(task.url(), task.target(), null,
                sourceCallback(task.sourceLabel(), listener), maxBytes);
        if (task.expectedSize() > 0 && task.target().length() != task.expectedSize()) {
            Files.deleteIfExists(task.target().toPath());
            throw new IOException(task.target().getName() + " size does not match metadata");
        }
        InstallHelpers.verifyDownloadedFile(task.target(), task.sha1());
    }

    private HttpUtil.SourceCallback sourceCallback(String label,
                                                    GameDownloader.DownloadListener listener) {
        return new HttpUtil.SourceCallback() {
            @Override
            public void onSource(String originalUrl, String candidateUrl,
                                 boolean mirror, String sourceName) {
                if (listener != null && mirror) {
                    listener.onStatus(label + "官方源响应较慢，切换到" + sourceName + "...");
                }
            }

            @Override
            public void onFailure(String candidateUrl, IOException error) {
                if (listener != null) {
                    listener.onStatus(label + "下载源失败，尝试下一个源: " + error.getMessage());
                }
            }
        };
    }

    record DownloadTask(String url, File target, String sha1, long expectedSize,
                        String sourceLabel) {
    }
}
