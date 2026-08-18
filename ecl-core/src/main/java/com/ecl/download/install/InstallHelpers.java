package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.task.TaskCancellationException;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.MinecraftRuleUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Shared download/verification primitives for the version install workflow. Mirrors the behaviour
 * the launcher shipped before the workflow was restructured: resumable, mirror-fallback downloads,
 * SHA-1 verification after download, and rule-aware native classifier selection.
 */
public final class InstallHelpers {

    private InstallHelpers() {
    }

    /** Throw {@link TaskCancellationException} when the running thread was interrupted. */
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancellationException("download cancelled");
        }
    }

    /** A single file the workflow wants on disk, verified by SHA-1 when the metadata provides one. */
    public record FileDownload(String url, File target, String sha1, String sourceLabel) {
    }

    public static boolean needsDownload(File target, String expectedSha1, boolean verifyExisting) {
        if (!target.isFile()) {
            return true;
        }
        return verifyExisting && hasSha1(expectedSha1) && !FileUtil.verifySha1(target, expectedSha1);
    }

    public static void verifyDownloadedFile(File target, String expectedSha1) throws IOException {
        if (!hasSha1(expectedSha1) || FileUtil.verifySha1(target, expectedSha1)) {
            return;
        }
        if (!target.delete()) {
            target.deleteOnExit();
        }
        throw new IOException(target.getName() + " 的 SHA-1 校验失败");
    }

    public static boolean hasSha1(String sha1) {
        return sha1 != null && !sha1.isBlank();
    }

    public static String nativeClassifierKey(com.google.gson.JsonObject library, String osName, String archBits) {
        if (library.has("natives")) {
            com.google.gson.JsonObject natives = library.getAsJsonObject("natives");
            if (natives.has(osName)) {
                return natives.get(osName).getAsString().replace("${arch}", archBits);
            }
        }
        return "natives-" + osName;
    }

    public static String nativeClassifierKey(com.google.gson.JsonObject library,
                                             com.google.gson.JsonObject classifiers,
                                             String osName, String archBits,
                                             String nativeClassifier) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        boolean arm = nativeClassifier != null && nativeClassifier.endsWith("-arm64");
        if (arm) {
            candidates.addAll(java.util.Arrays.asList(MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        }
        candidates.add(nativeClassifierKey(library, osName, archBits));
        candidates.addAll(java.util.Arrays.asList(MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        return candidates.stream().filter(classifiers::has).findFirst().orElse(null);
    }

    public static HttpUtil.SourceCallback sourceCallback(String label, InstallState state) {
        return new HttpUtil.SourceCallback() {
            @Override
            public void onSource(String originalUrl, String candidateUrl, boolean mirror, String sourceName) {
                if (mirror) {
                    state.setStatus(label + "官方源响应较慢，切换到" + sourceName + "...");
                }
            }

            @Override
            public void onFailure(String candidateUrl, IOException error) {
                state.setStatus(label + "下载源失败，尝试下一个源: " + error.getMessage());
            }
        };
    }

    /**
     * Download {@code tasks} concurrently on {@code executor}, forwarding file-count progress to the
     * workflow through {@code reporter}. The first failure aborts the phase.
     */
    public static void downloadConcurrently(List<FileDownload> tasks, String phase,
                                           ExecutorService executor, InstallState state,
                                           ProgressReporter reporter) throws IOException {
        if (tasks.isEmpty()) {
            state.setStatus(phase + "已是最新，无需下载");
            reporter.onFileProgress(0, 0);
            return;
        }
        int threadCount = Math.min(ECLConfig.DOWNLOAD_THREADS, tasks.size());
        state.setStatus("使用 " + threadCount + " 个线程下载" + phase + "，共 " + tasks.size() + " 个文件...");
        reporter.onFileProgress(0, tasks.size());

        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        List<Future<Void>> phaseTasks = new ArrayList<>(tasks.size());
        for (FileDownload task : tasks) {
            phaseTasks.add(completionService.submit(() -> {
                HttpUtil.downloadFile(task.url(), task.target(), sourceCallback(task.sourceLabel(), state));
                verifyDownloadedFile(task.target(), task.sha1());
                return null;
            }));
        }

        IOException firstError = null;
        int completed = 0;
        try {
            while (completed < tasks.size()) {
                try {
                    completionService.take().get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    IOException error = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException(cause == null ? e : cause);
                    if (firstError == null) {
                        firstError = error;
                    }
                }
                completed++;
                if (completed == tasks.size() || completed % 25 == 0) {
                    state.setStatus("下载" + phase + ": " + completed + "/" + tasks.size());
                }
                reporter.onFileProgress(completed, tasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskCancellationException(phase + "下载被中断", e);
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                phaseTasks.forEach(task -> task.cancel(true));
            }
        }

        if (firstError != null) {
            throw new IOException(phase + "下载失败: " + firstError.getMessage(), firstError);
        }
    }

    /** Hook the workflow uses to turn file-count progress into task progress events. */
    @FunctionalInterface
    public interface ProgressReporter {
        void onFileProgress(long done, long total);
    }
}
