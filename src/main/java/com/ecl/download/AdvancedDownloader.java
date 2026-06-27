package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced multi-thread downloader with resume support, retry logic and progress tracking.
 */
public class AdvancedDownloader {

    private static final int MAX_RETRIES = 3;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int THREAD_POOL_SIZE = 4;
    private static final long PART_SIZE = 5 * 1024 * 1024; // 5MB per part

    private DownloadListener listener;
    private final ExecutorService executor;

    private final AtomicLong totalDownloaded = new AtomicLong(0);
    private final AtomicLong totalSize = new AtomicLong(0);
    private final AtomicInteger completedFiles = new AtomicInteger(0);
    private final AtomicInteger totalFiles = new AtomicInteger(0);

    public AdvancedDownloader() {
        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    public interface DownloadListener {
        void onStart(int totalFiles);
        void onFileStart(String fileName);
        void onProgress(long downloaded, long total, int currentFile, int totalFiles);
        void onFileComplete(String fileName);
        void onError(String error);
        void onComplete();
        void onStatus(String message);
    }

    public void setListener(DownloadListener listener) {
        this.listener = listener;
    }

    public void shutdown() {
        executor.shutdown();
    }

    public void downloadFile(String url, File target) throws IOException {
        downloadFile(url, target, true);
    }

    public void downloadFile(String url, File target, boolean allowResume) throws IOException {
        long existingSize = 0;
        if (target.exists() && allowResume) {
            existingSize = target.length();
        }

        long finalExistingSize = existingSize;
        executeWithRetry(() -> {
            downloadFileInternal(url, target, finalExistingSize);
            return null;
        }, "下载文件", url);
    }

    private void downloadFileInternal(String urlStr, File target, long existingSize) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "ECLauncher/1.0");
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        // Resume support
        if (existingSize > 0) {
            conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
        }

        int code = conn.getResponseCode();
        if (code == 416) { // Range not satisfiable - file already complete
            conn.disconnect();
            return;
        }

        if (code != 200 && code != 206) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + urlStr);
        }

        long contentLength = conn.getContentLengthLong();
        if (code == 206 && existingSize > 0) {
            // Partial content, append to existing file
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(target, true)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
        } else {
            // Full download or overwrite
            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        conn.disconnect();
    }

    public void downloadWithProgress(String urlStr, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "ECLauncher/1.0");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        long contentLength = conn.getContentLengthLong();

        try (InputStream is = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                totalRead += read;
                if (listener != null) {
                    listener.onProgress(totalRead + totalDownloaded.get(), totalSize.get(),
                            completedFiles.get(), totalFiles.get());
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Download a list of files concurrently using thread pool.
     */
    public void downloadFiles(List<DownloadTask> tasks) throws InterruptedException {
        if (tasks.isEmpty()) return;
        totalFiles.set(tasks.size());
        completedFiles.set(0);
        totalDownloaded.set(0);

        if (listener != null) {
            listener.onStart(tasks.size());
        }

        CountDownLatch latch = new CountDownLatch(tasks.size());
        AtomicInteger errorCount = new AtomicInteger(0);

        for (DownloadTask task : tasks) {
            executor.submit(() -> {
                try {
                    if (listener != null) {
                        listener.onFileStart(task.url);
                    }

                    retryDownload(task.url, task.target, MAX_RETRIES);

                    completedFiles.incrementAndGet();
                    if (listener != null) {
                        listener.onFileComplete(task.url);
                        listener.onProgress(totalDownloaded.get(), totalSize.get(),
                                completedFiles.get(), totalFiles.get());
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    if (listener != null) {
                        listener.onError("下载失败: " + task.url + " - " + e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        if (errorCount.get() == 0) {
            if (listener != null) {
                listener.onComplete();
            }
        }
    }

    private void retryDownload(String url, File target, int maxRetries) throws IOException {
        IOException lastException = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                if (i > 0 && listener != null) {
                    listener.onStatus("重试下载 (" + i + "/" + maxRetries + "): " + target.getName());
                }
                downloadFile(url, target, true);
                return;
            } catch (IOException e) {
                lastException = e;
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(2000L * (i + 1)); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载中断", ie);
                    }
                }
            }
        }
        throw lastException;
    }

    private <T> T executeWithRetry(Callable<T> action, String description, String context) throws IOException {
        IOException lastException = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e instanceof IOException ? (IOException) e : new IOException(e);
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(2000L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("操作中断", ie);
                    }
                }
            }
        }
        throw lastException;
    }

    public static class DownloadTask {
        public final String url;
        public final File target;

        public DownloadTask(String url, File target) {
            this.url = url;
            this.target = target;
        }
    }
}
