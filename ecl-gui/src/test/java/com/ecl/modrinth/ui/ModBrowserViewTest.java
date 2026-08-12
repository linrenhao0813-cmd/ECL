package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

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
}
