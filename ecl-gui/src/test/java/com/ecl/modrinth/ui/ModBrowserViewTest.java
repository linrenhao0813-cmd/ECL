package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModBrowserViewTest {
    @Test
    void planFailureReasonPreservesNestedFailureMessage() {
        Throwable failure = new CompletionException(
                new ExecutionException(new IllegalStateException("所选依赖没有兼容版本")));

        assertEquals("所选依赖没有兼容版本", ModBrowserView.planFailureReason(failure));
    }

    @Test
    void planFailureReasonFallsBackWhenFailureHasNoMessage() {
        assertEquals(
                "操作失败，请查看日志获取详细信息",
                ModBrowserView.planFailureReason(new IllegalStateException()));
    }

    @Test
    void selectedUpdateBatchRunsEverySelectionSequentially() {
        List<String> started = new ArrayList<>();

        var result = ModBrowserView.runSequentialUpdateBatch(
                List.of("first", "second", "third"), item -> {
                    started.add(item);
                    return CompletableFuture.completedFuture(null);
                }).join();

        assertEquals(List.of("first", "second", "third"), started);
        assertEquals(3, result.succeeded());
        assertEquals(0, result.failures().size());
    }
}
