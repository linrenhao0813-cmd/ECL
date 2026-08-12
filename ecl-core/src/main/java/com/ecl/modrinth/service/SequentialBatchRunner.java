package com.ecl.modrinth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public final class SequentialBatchRunner {
    private SequentialBatchRunner() {
    }

    public static <T> CompletableFuture<Result<T>> run(
            List<T> items,
            Function<T, ? extends CompletableFuture<?>> operation
    ) {
        Objects.requireNonNull(operation, "operation");
        List<T> work = items == null ? List.of() : List.copyOf(items);
        CompletableFuture<MutableResult<T>> chain =
                CompletableFuture.completedFuture(new MutableResult<>());
        for (T item : work) {
            chain = chain.thenCompose(result -> execute(operation, item)
                    .handle((ignored, error) -> {
                        if (error == null) {
                            result.succeeded++;
                        } else {
                            result.failures.add(new Failure<>(item, unwrap(error)));
                        }
                        return result;
                    }));
        }
        return chain.thenApply(result -> new Result<>(
                work.size(), result.succeeded, List.copyOf(result.failures)));
    }

    private static <T> CompletableFuture<?> execute(
            Function<T, ? extends CompletableFuture<?>> operation,
            T item
    ) {
        try {
            CompletableFuture<?> future = operation.apply(item);
            return future == null
                    ? CompletableFuture.failedFuture(
                            new IllegalStateException("Batch operation returned null"))
                    : future;
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record Result<T>(int total, int succeeded, List<Failure<T>> failures) {
        public Result {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    public record Failure<T>(T item, Throwable cause) {
    }

    private static final class MutableResult<T> {
        private int succeeded;
        private final List<Failure<T>> failures = new ArrayList<>();
    }
}
