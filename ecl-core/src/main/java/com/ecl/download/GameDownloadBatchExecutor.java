package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.util.FileLockLease;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.ManagedLockPaths;
import com.ecl.util.NetworkUriPolicy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Executes verified file downloads and aggregates progress for one download phase. */
final class GameDownloadBatchExecutor {
    private static final long MAX_UNSIZED_LIBRARY_BYTES = 1024L * 1024 * 1024;
    private static final String DOWNLOADS_DIRECTORY = ".ecl-downloads";
    private final ExecutorService executor;
    private final int maxWorkers;

    GameDownloadBatchExecutor(ExecutorService executor, int maxWorkers) {
        this.executor = executor;
        this.maxWorkers = maxWorkers;
    }

    void download(List<DownloadTask> tasks, String phase,
                  GameDownloader.DownloadListener listener) throws IOException {
        if (tasks.isEmpty()) {
            if (listener != null) {
                listener.onStatus(phase + "已是最新，无需下载");
            }
            return;
        }

        // Repeated resource names may share one payload (the same hash under several names), and
        // repair/install plans can enumerate one target twice. Such duplicates contend for the very
        // same publication lock, so the second worker would always fail with "being repaired by
        // another task". Collapse them before scheduling, and keep the reported totals honest.
        List<DownloadTask> uniqueTasks = deduplicateByTarget(tasks);

        int threadCount = Math.min(Math.min(maxWorkers,
                HttpUtil.getDownloadMaxConcurrent()), uniqueTasks.size());
        if (listener != null) {
            listener.onStatus("使用 " + threadCount + " 个线程下载" + phase
                    + "，共 " + uniqueTasks.size() + " 个文件...");
        }
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        List<Future<Void>> phaseTasks = new ArrayList<>(uniqueTasks.size());
        for (DownloadTask task : uniqueTasks) {
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
            while (completed < uniqueTasks.size()) {
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
                if (listener != null && (completed == uniqueTasks.size() || completed % 25 == 0)) {
                    listener.onStatus("下载" + phase + ": " + completed + "/" + uniqueTasks.size());
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

    /**
     * Keeps the first declaration of every normalized target and rejects targets that were declared
     * with different digests or sizes: one of those declarations must be wrong, and silently picking
     * the first one would publish an unverified payload.
     */
    static List<DownloadTask> deduplicateByTarget(List<DownloadTask> tasks) throws IOException {
        Map<Path, DownloadTask> byTarget = new LinkedHashMap<>(tasks.size());
        for (DownloadTask task : tasks) {
            Path target = normalizedTarget(task);
            DownloadTask previous = byTarget.putIfAbsent(target, task);
            if (previous != null && !sameDeclaration(previous, task)) {
                throw new IOException("Conflicting declarations for one download target: " + target
                        + " (SHA-1 " + previous.sha1() + "/" + task.sha1()
                        + ", size " + previous.expectedSize() + "/" + task.expectedSize() + ")");
            }
        }
        return List.copyOf(byTarget.values());
    }

    private static Path normalizedTarget(DownloadTask task) {
        return task.target().toPath().toAbsolutePath().normalize();
    }

    private static boolean sameDeclaration(DownloadTask first, DownloadTask second) {
        return first.sha1().equalsIgnoreCase(second.sha1())
                && first.expectedSize() == second.expectedSize();
    }

    private void downloadAndVerify(DownloadTask task,
                                   GameDownloader.DownloadListener listener) throws IOException {
        downloadSingleVerified(task, listener, null);
    }

    void downloadSingleVerified(DownloadTask task, GameDownloader.DownloadListener listener,
                                HttpUtil.ProgressCallback progress) throws IOException {
        NetworkUriPolicy.requireArtifactDownload(
                URI.create(task.url()), task.sourceLabel() + " URL");
        long maxBytes = task.expectedSize() > 0
                ? task.expectedSize() : MAX_UNSIZED_LIBRARY_BYTES;
        Path target = task.target().toPath().toAbsolutePath().normalize();
        Path root = task.targetRoot().toAbsolutePath().normalize();
        Path stagingRoot = root.resolve(DOWNLOADS_DIRECTORY);
        if (target.startsWith(stagingRoot)) {
            throw new IOException("Download target uses the reserved staging directory: " + target);
        }
        FileUtil.validateExistingAncestors(root, target);
        Files.createDirectories(target.getParent());
        FileUtil.validateExistingAncestors(root, target);
        Path dataRoot = ECLConfig.getBaseDir().toPath().toAbsolutePath().normalize();
        Path lockPath = ManagedLockPaths.filePublication(dataRoot.resolve(".ecl-download-locks"), target);
        FileUtil.validateExistingAncestors(dataRoot, lockPath);
        try (FileLockLease ignored = FileLockLease.tryAcquire(lockPath)) {
            if (ignored == null) {
                throw new IOException("Shared artifact is being repaired by another task: " + target);
            }
            Path staging = stagingRoot.resolve(lockPath.getFileName() + ".download");
            Path partial = staging.resolveSibling(staging.getFileName() + ".part");
            Path partialMetadata = partial.resolveSibling(partial.getFileName() + ".meta");
            FileUtil.validateExistingAncestors(root, staging);
            FileUtil.validateExistingAncestors(root, partial);
            FileUtil.validateExistingAncestors(root, partialMetadata);
            try {
                HttpUtil.downloadFileWithProgress(task.url(), staging.toFile(), progress,
                        sourceCallback(task.sourceLabel(), listener), maxBytes);
                if (task.expectedSize() > 0 && Files.size(staging) != task.expectedSize()) {
                    throw new IOException(task.target().getName() + " size does not match metadata");
                }
                InstallHelpers.verifyDownloadedFile(staging.toFile(), task.sha1());
                FileUtil.validateExistingAncestors(root, target);
                FileUtil.moveReplacing(staging, target);
            } finally {
                // Keep HTTP partials and validators for retry; completed but invalid payloads are discarded.
                Files.deleteIfExists(staging);
            }
        }
    }

    HttpUtil.SourceCallback sourceCallback(String label,
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
                        String sourceLabel, Path targetRoot) {
        DownloadTask(String url, File target, String sha1, long expectedSize, String sourceLabel) {
            this(url, target, sha1, expectedSize, sourceLabel,
                    target.toPath().toAbsolutePath().normalize().getParent());
        }
    }
}
