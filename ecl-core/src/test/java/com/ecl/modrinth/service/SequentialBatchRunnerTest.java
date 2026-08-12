package com.ecl.modrinth.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SequentialBatchRunnerTest {
    @Test
    void continuesAfterIndividualFailuresAndPreservesOrder() {
        List<String> attempted = new ArrayList<>();

        SequentialBatchRunner.Result<String> result = SequentialBatchRunner.run(
                List.of("first", "broken", "last"),
                item -> {
                    attempted.add(item);
                    return "broken".equals(item)
                            ? CompletableFuture.failedFuture(new IllegalStateException("network failure"))
                            : CompletableFuture.completedFuture(null);
                }).join();

        assertEquals(List.of("first", "broken", "last"), attempted);
        assertEquals(3, result.total());
        assertEquals(2, result.succeeded());
        assertEquals(1, result.failures().size());
        assertEquals("broken", result.failures().getFirst().item());
        assertInstanceOf(IllegalStateException.class, result.failures().getFirst().cause());
    }

    @Test
    void recordsSynchronousOperationFailuresAndContinues() {
        List<Integer> attempted = new ArrayList<>();

        SequentialBatchRunner.Result<Integer> result = SequentialBatchRunner.run(
                List.of(1, 2, 3),
                item -> {
                    attempted.add(item);
                    if (item == 2) {
                        throw new IllegalArgumentException("bad item");
                    }
                    return CompletableFuture.completedFuture(null);
                }).join();

        assertEquals(List.of(1, 2, 3), attempted);
        assertEquals(2, result.succeeded());
        assertInstanceOf(IllegalArgumentException.class, result.failures().getFirst().cause());
    }
}
