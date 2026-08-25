package com.ecl.util;

import java.io.IOException;

/**
 * Token-bucket download throttle.
 *
 * <p>Instances are independent so different download domains can carry separate limits. The
 * process-wide {@link #defaultLimiter()} keeps the legacy static API working for code that does
 * not need an isolated limiter; {@link #reset()} lets tests and runtime reconfiguration restore a
 * clean state without leaking into other tests.</p>
 */
final class DownloadRateLimiter {
    /** Process-wide limiter used by the legacy static entry points. */
    private static final DownloadRateLimiter DEFAULT = new DownloadRateLimiter();

    private final Object lock = new Object();
    private long bytesPerSecond;
    private double availableTokens;
    private long tokensUpdatedAt;

    DownloadRateLimiter() {
    }

    static DownloadRateLimiter defaultLimiter() {
        return DEFAULT;
    }

    void setBytesPerSecond(long value) {
        synchronized (lock) {
            bytesPerSecond = Math.max(0, value);
            availableTokens = bytesPerSecond;
            tokensUpdatedAt = System.nanoTime();
        }
    }

    long getBytesPerSecond() {
        synchronized (lock) {
            return bytesPerSecond;
        }
    }

    /** Restore the initial state (rate 0, no tokens, no start time). */
    void reset() {
        synchronized (lock) {
            bytesPerSecond = 0;
            availableTokens = 0;
            tokensUpdatedAt = 0;
        }
    }

    void acquire(int bytes) throws IOException {
        while (true) {
            long waitNanos;
            synchronized (lock) {
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

    /** Fail fast when the calling thread has been interrupted. */
    static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("HTTP operation interrupted");
        }
    }

    // ---- Legacy process-wide static API (delegates to the default limiter) ----

    static void setDefaultBytesPerSecond(long value) {
        DEFAULT.setBytesPerSecond(value);
    }

    static long getDefaultBytesPerSecond() {
        return DEFAULT.getBytesPerSecond();
    }

    static void acquireDefault(int bytes) throws IOException {
        DEFAULT.acquire(bytes);
    }

    /** Reset the process-wide default limiter (mainly for tests). */
    static void resetDefault() {
        DEFAULT.reset();
    }
}
