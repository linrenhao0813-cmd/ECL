package com.ecl.modrinth.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModFileDownloadServiceTest {
    @Test
    void cancellingBatchCancelsQueuedDownloadTask(@TempDir Path tempDirectory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            Path temporaryFile = tempDirectory.resolve("queued.jar");
            ModDownloadRequest request = new ModDownloadRequest(
                    URI.create("https://example.invalid/queued.jar"),
                    "queued.jar",
                    temporaryFile,
                    Map.of(),
                    0);
            var result = new ModFileDownloadService(executor, new HashVerifier())
                    .downloadAll(java.util.List.of(request), null);

            assertTrue(result.cancel(true));
            releaseBlocker.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(temporaryFile));
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }
}
