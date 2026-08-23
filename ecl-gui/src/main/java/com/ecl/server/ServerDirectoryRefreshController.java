package com.ecl.server;

import com.ecl.util.ThreadFactories;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Loads the remote server directory while preventing concurrent refreshes. */
final class ServerDirectoryRefreshController implements AutoCloseable {
    private final ServerDirectoryService directoryService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            ThreadFactories.daemon("ecl-server-directory"));
    private final AtomicBoolean refreshing = new AtomicBoolean();

    ServerDirectoryRefreshController(ServerDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    boolean refresh(boolean forceRefresh, BooleanSupplier closed,
                    BiConsumer<ServerDirectoryService.DirectorySnapshot, Throwable> completed) {
        if (closed.getAsBoolean() || !refreshing.compareAndSet(false, true)) {
            return false;
        }
        CompletableFuture.supplyAsync(() -> directoryService.load(forceRefresh), executor)
                .whenComplete((snapshot, error) -> {
                    refreshing.set(false);
                    completed.accept(snapshot, error);
                });
        return true;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
