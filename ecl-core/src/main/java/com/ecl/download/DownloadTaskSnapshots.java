package com.ecl.download;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Converts mutable task state into immutable UI-facing snapshots. */
final class DownloadTaskSnapshots {
    private DownloadTaskSnapshots() {
    }

    static DownloadTaskCenter.TaskSnapshot snapshot(DownloadTaskEntry<?> entry) {
        return new DownloadTaskCenter.TaskSnapshot(entry.id, entry.title, entry.detail, entry.status,
                entry.progress, entry.downloadedBytes, entry.totalBytes,
                entry.speedBytesPerSecond, entry.attempts, entry.errorMessage,
                entry.createdAtMillis, entry.updatedAtMillis);
    }

    static boolean isTerminal(DownloadTaskCenter.Status status) {
        return status == DownloadTaskCenter.Status.COMPLETED
                || status == DownloadTaskCenter.Status.FAILED
                || status == DownloadTaskCenter.Status.CANCELLED;
    }

    static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof CompletionException || current instanceof ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
