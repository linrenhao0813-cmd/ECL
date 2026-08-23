package com.ecl.download;

import java.util.concurrent.CompletableFuture;

/** Mutable state owned by one queued download task. Access is guarded by the task center lock. */
final class DownloadTaskEntry<T> {
    final String id;
    final String title;
    final DownloadTaskCenter.Operation<T> operation;
    final CompletableFuture<T> completion = new CompletableFuture<>();
    final long createdAtMillis = System.currentTimeMillis();
    volatile Thread runner;
    volatile Runnable cancellationHook;
    volatile boolean cancelRequested;
    DownloadTaskCenter.Status status = DownloadTaskCenter.Status.QUEUED;
    String detail = "等待开始";
    double progress;
    long downloadedBytes;
    long totalBytes;
    long speedBytesPerSecond;
    long progressTimestamp;
    long progressBytes;
    int attempts;
    String errorMessage = "";
    long updatedAtMillis = createdAtMillis;

    DownloadTaskEntry(String id, String title, DownloadTaskCenter.Operation<T> operation) {
        this.id = id;
        this.title = title;
        this.operation = operation;
    }
}
