package com.ecl.modrinth.ui;

import com.ecl.modrinth.service.SequentialBatchRunner;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Runs selected mod updates sequentially to preserve instance transaction ordering. */
final class ModBatchUpdates {
    private ModBatchUpdates() {
    }

    static <T> CompletableFuture<SequentialBatchRunner.Result<T>> run(
            List<T> updates, Function<T, ? extends CompletableFuture<?>> operation) {
        return SequentialBatchRunner.run(List.copyOf(updates), operation);
    }
}
