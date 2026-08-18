package com.ecl.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadTaskCenterTest {
    @Test
    void queuesTasksByConfiguredConcurrency() throws Exception {
        try (DownloadTaskCenter center = new DownloadTaskCenter(1, 0)) {
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
        try (DownloadTaskCenter center = new DownloadTaskCenter(1, 0)) {
            AtomicInteger attempts = new AtomicInteger();
            var first = center.submit("retry me", context -> {
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
        try (DownloadTaskCenter center = new DownloadTaskCenter(1, 0)) {
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
        try (DownloadTaskCenter center = new DownloadTaskCenter(1, 0)) {
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
}
