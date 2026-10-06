package com.ecl.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Applies an idle read timeout after Java HTTP clients have delivered response headers. */
final class ResponseBodyInputStream extends FilterInputStream {
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(
            1, ThreadFactories.daemon("ecl-http-body-timeout"));

    static {
        TIMEOUTS.setRemoveOnCancelPolicy(true);
    }

    private final long timeoutMillis;

    ResponseBodyInputStream(InputStream input, long timeoutMillis) {
        super(input);
        this.timeoutMillis = Math.max(1, timeoutMillis);
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        AtomicInteger state = new AtomicInteger();
        var timeout = TIMEOUTS.schedule(() -> {
            if (state.compareAndSet(0, 2)) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // The blocked reader reports the timeout when the HTTP stream closes.
                }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            int read = in.read(bytes, offset, length);
            if (!state.compareAndSet(0, 1)) {
                throw new SocketTimeoutException("HTTP response body read timed out");
            }
            return read;
        } catch (IOException failure) {
            if (state.get() == 2) {
                SocketTimeoutException expired = new SocketTimeoutException("HTTP response body read timed out");
                expired.initCause(failure);
                throw expired;
            }
            throw failure;
        } finally {
            state.compareAndSet(0, 1);
            timeout.cancel(false);
        }
    }
}
