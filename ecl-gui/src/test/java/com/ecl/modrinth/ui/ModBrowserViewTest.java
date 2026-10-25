package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void selectedUpdateBatchKeepsSelectionSnapshotWhileFirstUpdateIsPending() {
        List<String> selection = new ArrayList<>(List.of("first", "second"));
        List<String> started = new ArrayList<>();
        CompletableFuture<Void> firstUpdate = new CompletableFuture<>();

        var batch = ModBrowserView.runSequentialUpdateBatch(selection, item -> {
            started.add(item);
            return "first".equals(item) ? firstUpdate : CompletableFuture.completedFuture(null);
        });
        assertEquals(List.of("first"), started);
        selection.clear();
        firstUpdate.complete(null);

        assertEquals(2, batch.join().succeeded());
        assertEquals(List.of("first", "second"), started);
    }

    @Test
    void selectedUpdateBatchRejectsMissingSelection() {
        assertThrows(NullPointerException.class, () -> ModBrowserView.runSequentialUpdateBatch(
                null, item -> CompletableFuture.completedFuture(null)));
    }
}
