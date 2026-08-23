package com.ecl.modrinth.ui.viewmodel;

import com.ecl.download.DownloadTaskCenter;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;

import java.util.concurrent.CompletableFuture;

/** Owns the visible state and cancellation handles of the current browser operation. */
final class ModBrowserOperationState {
    private final BooleanProperty loading;
    private final StringProperty errorMessage;
    private final StringProperty currentOperation;
    private final BooleanProperty cancellable;
    private final DoubleProperty overallProgress;
    private volatile CompletableFuture<?> activeRequest;
    private volatile DownloadTaskCenter.TaskHandle<?> activeDownload;

    ModBrowserOperationState(BooleanProperty loading, StringProperty errorMessage,
                             StringProperty currentOperation, BooleanProperty cancellable,
                             DoubleProperty overallProgress) {
        this.loading = loading;
        this.errorMessage = errorMessage;
        this.currentOperation = currentOperation;
        this.cancellable = cancellable;
        this.overallProgress = overallProgress;
    }

    void begin(String operation, boolean canCancel) {
        Platform.runLater(() -> {
            loading.set(true);
            errorMessage.set("");
            currentOperation.set(operation);
            cancellable.set(canCancel);
        });
    }

    void finish() {
        activeDownload = null;
        loading.set(false);
        cancellable.set(false);
        overallProgress.set(0);
    }

    void track(CompletableFuture<?> request) {
        activeRequest = request;
    }

    void trackDownload(DownloadTaskCenter.TaskHandle<?> task) {
        activeDownload = task;
    }

    void updateProgress(long downloaded, long total, String fileName) {
        Platform.runLater(() -> {
            overallProgress.set(total <= 0 ? -1 : Math.min(1, (double) downloaded / total));
            currentOperation.set("正在下载 " + fileName);
        });
    }

    void cancel() {
        DownloadTaskCenter.TaskHandle<?> task = activeDownload;
        if (task != null) {
            task.cancel();
        }
        CompletableFuture<?> request = activeRequest;
        if (request != null && !request.isDone()) {
            request.cancel(true);
        }
    }
}
