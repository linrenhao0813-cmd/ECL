package com.ecl.util;

import java.io.IOException;

/** Process-wide token bucket used by streaming downloads. */
final class DownloadRateLimiter {
    private static final Object LOCK = new Object();

    private static long bytesPerSecond;
    private static double availableTokens;
    private static long tokensUpdatedAt;

    private DownloadRateLimiter() {
    }

    static void setBytesPerSecond(long value) {
        synchronized (LOCK) {
            bytesPerSecond = Math.max(0, value);
            availableTokens = bytesPerSecond;
            tokensUpdatedAt = System.nanoTime();
        }
    }

    static long getBytesPerSecond() {
        synchronized (LOCK) {
            return bytesPerSecond;
        }
    }

    static void acquire(int bytes) throws IOException {
        while (true) {
            long waitNanos;
            synchronized (LOCK) {
                long rate = bytesPerSecond;
                if (rate <= 0) {
                    return;
                }
                long now = System.nanoTime();
                if (tokensUpdatedAt == 0) {
                    tokensUpdatedAt = now;
                }
                long elapsed = Math.max(0, now - tokensUpdatedAt);
                availableTokens = Math.min(rate,
                        availableTokens + elapsed * (double) rate / 1_000_000_000d);
                tokensUpdatedAt = now;
                if (availableTokens >= bytes) {
                    availableTokens -= bytes;
                    return;
                }
                waitNanos = (long) Math.ceil((bytes - availableTokens)
                        * 1_000_000_000d / rate);
            }
            try {
                long boundedWait = Math.min(waitNanos, 200_000_000L);
                Thread.sleep(boundedWait / 1_000_000L,
                        (int) (boundedWait % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", interrupted);
            }
        }
    }

    static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("HTTP operation interrupted");
        }
    }
}
