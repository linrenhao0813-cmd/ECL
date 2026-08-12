package com.ecl.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskExecutorTest {

    private static final class RecordingListener implements TaskListener {
        final java.util.List<String> events =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onQueued(Task<?> task) {
            events.add("queued:" + task.name());
        }

        @Override
        public void onStarted(Task<?> task) {
            events.add("started:" + task.name());
        }

        @Override
        public void onProgress(Task<?> task, double fraction) {
            events.add("progress:" + task.name() + ":" + fraction);
        }

        @Override
        public void onFinished(Task<?> task) {
            events.add("finished:" + task.name());
            terminal.countDown();
        }

        @Override
        public void onFailed(Task<?> task, Throwable cause) {
            events.add("failed:" + task.name());
            failure.set(cause);
            terminal.countDown();
        }

        @Override
        public void onCancelled(Task<?> task) {
            events.add("cancelled:" + task.name());
            terminal.countDown();
        }

        boolean awaitTerminal() throws InterruptedException {
            return terminal.await(5, TimeUnit.SECONDS);
        }
    }

    private static <T> Task<T> leaf(String name, T result) {
        return new Task<T>(name) {
            @Override
            protected T execute() {
                return result;
            }
        };
    }

    @Test
    void executesLeafTaskAndReturnsResult() throws Exception {
        TaskExecutor executor = new TaskExecutor();
        try {
            Task<String> task = leaf("leaf", "done");
            TaskFuture<String> future = executor.submit(task);
            assertEquals("done", future.await());
            assertTrue(future.isDone());
            assertFalse(future.isCancelled());
        } finally {
            executor.close();
        }
    }

    @Test
    void runsDependenciesBeforeParent() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            Task<String> parent = leaf("parent", "ok").dependsOn(
                    leaf("dep-a", null), leaf("dep-b", null));
            executor.submit(parent).await();

            List<String> events = List.copyOf(listener.events);
            int depA = events.indexOf("started:dep-a");
            int depB = events.indexOf("started:dep-b");
            int parentStarted = events.indexOf("started:parent");
            int parentFinished = events.indexOf("finished:parent");
            assertTrue(depA >= 0 && depB >= 0 && parentStarted >= 0);
            assertTrue(depA < parentStarted, "dep-a must run before parent");
            assertTrue(depB < parentStarted, "dep-b must run before parent");
            assertTrue(parentStarted < parentFinished);
        } finally {
            executor.close();
        }
    }

    @Test
    void dependencyFailureAbortsParent() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            Task<Void> parent = new Task<Void>("parent") {
                @Override
                protected Void execute() {
                    throw new AssertionError("parent must not run after dependency failure");
                }
            }.dependsOn(new Task<Void>("failing") {
                @Override
                protected Void execute() {
                    throw new IllegalStateException("dep boom");
                }
            });

            assertThrows(TaskExecutionException.class,
                    () -> executor.submit(parent).awaitUnchecked());
            assertTrue(listener.awaitTerminal());
            List<String> events = List.copyOf(listener.events);
            assertEquals("failed:failing", events.get(events.size() - 2));
            assertEquals("failed:parent", events.get(events.size() - 1));
            assertTrue(listener.failure.get() instanceof IllegalStateException);
        } finally {
            executor.close();
        }
    }

    @Test
    void cancellationBeforeStartPreventsExecution() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            Task<String> task = new Task<String>("never-runs") {
                @Override
                protected String execute() {
                    throw new AssertionError("cancelled task must not execute");
                }
            };
            executor.cancel(task);
            TaskFuture<String> future = executor.submit(task);

            assertThrows(TaskCancellationException.class, future::await);
            assertTrue(listener.awaitTerminal());
            assertTrue(listener.events.contains("cancelled:never-runs"));
        } finally {
            executor.close();
        }
    }

    @Test
    void interruptingRunningTaskIsReportedAsCancellation() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            Task<Void> task = new Task<Void>("sleep") {
                @Override
                protected Void execute() throws InterruptedException {
                    Thread.sleep(60_000);
                    return null;
                }
            };
            TaskFuture<Void> future = executor.submit(task);
            Thread.sleep(200); // let the worker start
            assertTrue(executor.cancel(future));

            assertThrows(TaskCancellationException.class, future::await);
            assertTrue(listener.awaitTerminal());
            assertTrue(listener.events.contains("cancelled:sleep"));
        } finally {
            executor.close();
        }
    }

    @Test
    void cooperativeCancellationFlagStopsLongLoop() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Task<Void> task = new Task<Void>("loop") {
                @Override
                protected Void execute() {
                    started.complete(null);
                    long count = 0;
                    while (!isCancelled()) {
                        count++;
                        if (count > 10_000_000L) {
                            throw new AssertionError("loop ignored cancellation");
                        }
                    }
                    throw new TaskCancellationException("loop cancelled");
                }
            };
            TaskFuture<Void> future = executor.submit(task);
            started.join(); // let the loop begin before we cancel, so the flag lands in time
            executor.cancel(task);
            assertThrows(TaskCancellationException.class, future::await);
            assertTrue(listener.awaitTerminal());
            assertTrue(listener.events.contains("cancelled:loop"));
        } finally {
            executor.close();
        }
    }

    @Test
    void progressReportsReachTheListener() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            Task<Void> task = new Task<Void>("progressed") {
                @Override
                protected Void execute() {
                    reportProgress(0.5);
                    return null;
                }
            };
            executor.submit(task).await();

            assertTrue(listener.events.contains("progress:progressed:0.5"));
        } finally {
            executor.close();
        }
    }

    @Test
    void awaitUncheckedUnwrapsFailureCauses() throws Exception {
        TaskExecutor executor = new TaskExecutor();
        try {
            Task<Void> failing = new Task<Void>("boom") {
                @Override
                protected Void execute() {
                    throw new IllegalArgumentException("inner failure");
                }
            };
            TaskExecutionException thrown =
                    assertThrows(TaskExecutionException.class, () -> executor.await(failing));
            assertTrue(thrown.getCause() instanceof IllegalArgumentException);
        } finally {
            executor.close();
        }
    }

    @Test
    void leavesThatFailDoNotCancelIndependentSiblingTasks() throws Exception {
        TaskExecutor executor = new TaskExecutor();
        try {
            Task<String> good = leaf("good", "result");
            Task<Void> bad = new Task<Void>("bad") {
                @Override
                protected Void execute() {
                    throw new IllegalArgumentException("expected");
                }
            };
            TaskFuture<String> goodFuture = executor.submit(good);
            TaskFuture<Void> badFuture = executor.submit(bad);

            assertThrows(TaskExecutionException.class, badFuture::awaitUnchecked);
            assertEquals("result", goodFuture.await());
        } finally {
            executor.close();
        }
    }

    @Test
    void rootTaskQueuedEventIsReported() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            executor.submit(leaf("queued", null)).await();
            assertTrue(listener.events.contains("queued:queued"),
                    () -> "expected a queued event but got " + listener.events);
        } finally {
            executor.close();
        }
    }

    @Test
    void nestedDependenciesRunTopologicallyAndSharedOnesOnlyOnce() throws Exception {
        RecordingListener listener = new RecordingListener();
        TaskExecutor executor = new TaskExecutor(listener);
        try {
            Task<Void> base = leaf("base", null);
            Task<Void> left = TaskExecutorTest.<Void>leaf("left", null).dependsOn(base);
            Task<Void> right = TaskExecutorTest.<Void>leaf("right", null).dependsOn(base);
            Task<Void> parent = TaskExecutorTest.<Void>leaf("parent", null).dependsOn(left, right);

            executor.submit(parent).await();

            List<String> events = List.copyOf(listener.events);
            assertTrue(events.indexOf("started:base") < events.indexOf("started:left"));
            assertTrue(events.indexOf("started:base") < events.indexOf("started:right"));
            assertTrue(events.indexOf("started:left") < events.indexOf("started:parent"));
            assertTrue(events.indexOf("started:right") < events.indexOf("started:parent"));
            assertEquals(1, events.stream().filter("finished:base"::equals).count(),
                    "shared dependency must execute exactly once");
        } finally {
            executor.close();
        }
    }

    @Test
    void cyclicDependencyGraphFailsFast() throws Exception {
        TaskExecutor executor = new TaskExecutor();
        try {
            Task<Void> a = leaf("a", null);
            Task<Void> b = leaf("b", null);
            a.dependsOn(b);
            b.dependsOn(a);

            assertThrows(TaskExecutionException.class, () -> executor.await(a));
        } finally {
            executor.close();
        }
    }

    @Test
    void awaitPropagatesCancellationOfPeerCancelledFuture() throws Exception {
        TaskExecutor executor = new TaskExecutor();
        AtomicReference<TaskFuture<Void>> ref = new AtomicReference<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        Task<Void> task = new Task<Void>("blocker") {
            @Override
            protected Void execute() {
                started.complete(null);
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    throw new TaskCancellationException("blocker interrupted", e);
                }
                return null;
            }
        };
        try {
            TaskFuture<Void> future = executor.submit(task);
            ref.set(future);
            started.join();
            executor.cancel(future);
            assertThrows(TaskCancellationException.class, future::await);
        } finally {
            executor.close();
        }
    }
}
