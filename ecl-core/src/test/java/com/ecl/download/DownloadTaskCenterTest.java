package com.ecl.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadTaskCenterTest {
    @Test
    void completionHandlerFollowsRetriesAndBlocksOldAttempts() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger notifications = new AtomicInteger();
            var initial = center.submit("retry", () -> context -> {
                if (attempts.incrementAndGet() == 1) throw new IOException("temporary");
                return "done";
            }, (result, error) -> notifications.incrementAndGet());
            assertThrows(ExecutionException.class, () -> initial.completion().get(5, TimeUnit.SECONDS));
            assertTrue(center.canRetry(initial.id()));
            var retry = initial.retry();
            assertNotNull(retry);
            assertEquals("done", retry.completion().get(5, TimeUnit.SECONDS));
            assertEquals(2, notifications.get());
            assertFalse(center.canRetry(initial.id()));
            assertNull(initial.retry());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void queuedCancellationNotifiesCompletionOutsideTheQueueLock() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1);
             var observer = Executors.newSingleThreadExecutor()) {
            CountDownLatch release = new CountDownLatch(1);
            var first = center.submit("running", context -> {
                release.await(5, TimeUnit.SECONDS);
                return null;
            });
            AtomicBoolean notified = new AtomicBoolean();
            AtomicBoolean queueReadable = new AtomicBoolean();
            var queued = center.submit("queued", () -> context -> {
                throw new AssertionError("Cancelled queued operation must not run");
            }, (result, error) -> {
                notified.set(true);
                try {
                    queueReadable.set(observer.submit(() -> !center.snapshots().isEmpty())
                            .get(1, TimeUnit.SECONDS));
                } catch (Exception ignored) {
                    queueReadable.set(false);
                }
            });
            try {
                assertTrue(queued.cancel());
                assertTrue(queued.completion().isCancelled());
                assertTrue(notified.get());
                assertTrue(queueReadable.get());
            } finally {
                release.countDown();
            }
            first.completion().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentRetriesCreateOnlyOneNewAttempt() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1);
             var callers = Executors.newFixedThreadPool(2)) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();
            var initial = center.submit("retry", () -> context -> {
                if (attempts.incrementAndGet() == 1) throw new IOException("temporary");
                release.await(5, TimeUnit.SECONDS);
                return null;
            });
            assertThrows(ExecutionException.class, () -> initial.completion().get(5, TimeUnit.SECONDS));
            var first = callers.submit(initial::retry);
            var second = callers.submit(initial::retry);
            try {
                var a = first.get(5, TimeUnit.SECONDS);
                var b = second.get(5, TimeUnit.SECONDS);
                assertEquals(1, (a == null ? 0 : 1) + (b == null ? 0 : 1));
                release.countDown();
                (a == null ? b : a).completion().get(5, TimeUnit.SECONDS);
                assertEquals(2, attempts.get());
            } finally {
                release.countDown();
            }
        }
    }
    @Test
    void failurePreservesErrorAndStartsNextQueuedTask() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            CountDownLatch release = new CountDownLatch(1);
            IOException failure = new IOException("connection lost");
            var first = center.submit("failing", context -> {
                release.await(5, TimeUnit.SECONDS);
                throw failure;
            });
            var next = center.submit("next", context -> "downloaded");
            assertEquals(DownloadTaskCenter.Status.QUEUED, next.snapshot().status());

            release.countDown();
            ExecutionException actual = assertThrows(ExecutionException.class,
                    () -> first.completion().get(5, TimeUnit.SECONDS));
            assertEquals(failure, actual.getCause());
            assertEquals("downloaded", next.completion().get(5, TimeUnit.SECONDS));
            assertEquals(DownloadTaskCenter.Status.FAILED, first.snapshot().status());
            assertEquals("connection lost", first.snapshot().errorMessage());
        }
    }

    @Test
    void failureAfterCancellationRemainsCancelledAndStartsNextTask() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var first = center.submit("cancel then fail", context -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Some downloaders translate interrupted I/O into a general failure.
                }
                throw new IOException("stream closed");
            });
            var next = center.submit("next", context -> "finished");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(first.cancel());
            release.countDown();

            assertEquals("finished", next.completion().get(5, TimeUnit.SECONDS));
            assertTrue(first.completion().isCancelled());
            assertEquals(DownloadTaskCenter.Status.CANCELLED, first.snapshot().status());
            assertEquals("", first.snapshot().errorMessage());
        }
    }

    @Test
    void queuesTasksByConfiguredConcurrency() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            var first = center.submit("first", context -> {
                firstStarted.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
                return null;
            });
            var second = center.submit("second", context -> null);

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertEquals(DownloadTaskCenter.Status.QUEUED, second.snapshot().status());
            releaseFirst.countDown();
            first.completion().get(5, TimeUnit.SECONDS);
            second.completion().get(5, TimeUnit.SECONDS);
            assertEquals(DownloadTaskCenter.Status.COMPLETED, second.snapshot().status());
        }
    }

    @Test
    void failedTaskCanBeRetried() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            AtomicInteger attempts = new AtomicInteger();
            var first = center.submit("retry me", () -> context -> {
                if (attempts.incrementAndGet() == 1) throw new IOException("temporary failure");
                return null;
            });
            assertTrue(first.completion().handle((value, error) -> true).get(5, TimeUnit.SECONDS));
            assertEquals(DownloadTaskCenter.Status.FAILED, first.snapshot().status());
            var retry = first.retry();
            assertNotNull(retry);
            retry.completion().get(5, TimeUnit.SECONDS);
            assertEquals(2, attempts.get());
            assertEquals(2, retry.snapshot().attempts());
        }
    }

    @Test
    void queuedTaskCanBeCancelled() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            CountDownLatch release = new CountDownLatch(1);
            var blocker = center.submit("blocker", context -> {
                release.await(5, TimeUnit.SECONDS);
                return null;
            });
            var queued = center.submit("queued", context -> null);
            assertTrue(queued.cancel());
            assertEquals(DownloadTaskCenter.Status.CANCELLED, queued.snapshot().status());
            release.countDown();
            blocker.completion().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void runningCancellationKeepsConcurrencySlotUntilOperationStops() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch allowFirstToStop = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            var first = center.submit("running", context -> {
                firstStarted.countDown();
                while (allowFirstToStop.getCount() > 0) {
                    try {
                        allowFirstToStop.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Deliberately emulate a downloader that needs time to honour cancellation.
                    }
                }
                return null;
            });
            var second = center.submit("next", context -> {
                secondStarted.countDown();
                return null;
            });

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(first.cancel());
            assertEquals(DownloadTaskCenter.Status.CANCELLING, first.snapshot().status());
            assertEquals(DownloadTaskCenter.Status.QUEUED, second.snapshot().status());
            assertEquals(1, secondStarted.getCount());

            allowFirstToStop.countDown();
            second.completion().get(5, TimeUnit.SECONDS);
            assertEquals(DownloadTaskCenter.Status.CANCELLED, first.snapshot().status());
            assertEquals(DownloadTaskCenter.Status.COMPLETED, second.snapshot().status());
        }
    }

    @Test
    void notifiesListenersWhenTaskIsQueuedBehindTheConcurrencyLimit() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean queuedWasPublished = new AtomicBoolean();
            center.submit("blocker", context -> {
                release.await(5, TimeUnit.SECONDS);
                return null;
            });
            center.addListener(tasks -> {
                if (tasks.stream().anyMatch(task -> "queued".equals(task.title())
                        && task.status() == DownloadTaskCenter.Status.QUEUED)) {
                    queuedWasPublished.set(true);
                }
            });

            var queued = center.submit("queued", context -> null);
            assertEquals(DownloadTaskCenter.Status.QUEUED, queued.snapshot().status());
            assertTrue(queuedWasPublished.get());
            release.countDown();
        }
    }

    @Test
    void automaticallyPrunesOldFinishedTaskHistory() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1)) {
            int submitted = DownloadTaskCenter.MAX_RETAINED_FINISHED_TASKS + 5;
            for (int i = 0; i < submitted; i++) {
                center.submit("completed-" + i, context -> null)
                        .completion().get(5, TimeUnit.SECONDS);
            }

            assertEquals(DownloadTaskCenter.MAX_RETAINED_FINISHED_TASKS,
                    center.snapshots().size());
            assertEquals("completed-5", center.snapshots().getFirst().title());
        }
    }

    @Test
    void closedCenterRejectsFactoryBeforeCreatingAnOperation() {
        try (DownloadTaskCenter center = new DownloadTaskCenter()) {
            center.close();
            AtomicBoolean created = new AtomicBoolean();

            assertThrows(IllegalStateException.class, () -> center.submit(
                    "closed", () -> {
                        created.set(true);
                        return context -> null;
                    }));
            assertFalse(created.get());
        }
    }
}
