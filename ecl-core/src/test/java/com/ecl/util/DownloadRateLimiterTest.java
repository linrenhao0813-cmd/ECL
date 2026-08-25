package com.ecl.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Verifies that the download limiter is instance-scoped and resettable for tests. */
class DownloadRateLimiterTest {

    @Test
    void isolatedInstanceDoesNotShareStateWithDefault() throws Exception {
        DownloadRateLimiter limiter = new DownloadRateLimiter();
        limiter.setBytesPerSecond(1_000_000L);
        assertEquals(1_000_000L, limiter.getBytesPerSecond());

        // The process-wide default must remain untouched by the isolated instance.
        long defaultRateBefore = DownloadRateLimiter.getDefaultBytesPerSecond();
        DownloadRateLimiter.resetDefault();
        assertEquals(0L, DownloadRateLimiter.getDefaultBytesPerSecond());
        assertNotEquals(limiter.getBytesPerSecond(), DownloadRateLimiter.getDefaultBytesPerSecond());
        assertEquals(defaultRateBefore, DownloadRateLimiter.getDefaultBytesPerSecond());
    }

    @Test
    void zeroRateMeansNoThrottlingAndResetRestoresIt() throws Exception {
        DownloadRateLimiter limiter = new DownloadRateLimiter();
        assertDoesNotThrow(() -> limiter.acquire(4096));

        limiter.setBytesPerSecond(1_000_000L);
        assertDoesNotThrow(() -> limiter.acquire(1024));

        limiter.reset();
        assertEquals(0L, limiter.getBytesPerSecond());
        assertDoesNotThrow(() -> limiter.acquire(4096));
    }

    @Test
    void resetDefaultClearsProcessWideRate() throws Exception {
        DownloadRateLimiter.setDefaultBytesPerSecond(500_000L);
        try {
            assertEquals(500_000L, DownloadRateLimiter.getDefaultBytesPerSecond());
            assertDoesNotThrow(() -> DownloadRateLimiter.acquireDefault(512));
        } finally {
            DownloadRateLimiter.resetDefault();
        }
        assertEquals(0L, DownloadRateLimiter.getDefaultBytesPerSecond());
    }
}
