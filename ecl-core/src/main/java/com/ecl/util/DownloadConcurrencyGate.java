package com.ecl.util;

import com.ecl.ECLConfig;

import java.io.InterruptedIOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Process-wide concurrency gate shared by every binary download implementation. */
final class DownloadConcurrencyGate {
    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Condition PERMIT_AVAILABLE = LOCK.newCondition();
    private static int limit = 2;
    private static int active;

    private DownloadConcurrencyGate() {
    }

    static void setLimit(int value) {
        LOCK.lock();
        try {
            limit = ECLConfig.clampDownloadConcurrency(value);
            PERMIT_AVAILABLE.signalAll();
        } finally {
            LOCK.unlock();
        }
    }

    static int limit() {
        LOCK.lock();
        try {
            return limit;
        } finally {
            LOCK.unlock();
        }
    }

    static Permit acquire() throws InterruptedIOException {
        LOCK.lock();
        try {
            while (active >= limit) {
                try {
                    PERMIT_AVAILABLE.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interrupted =
                            new InterruptedIOException("Interrupted while waiting for a download slot");
                    interrupted.initCause(error);
                    throw interrupted;
                }
            }
            active++;
            return new Permit();
        } finally {
            LOCK.unlock();
        }
    }

    static final class Permit implements AutoCloseable {
        private boolean released;

        private Permit() {
        }

        @Override
        public void close() {
            LOCK.lock();
            try {
                if (!released) {
                    released = true;
                    active--;
                    PERMIT_AVAILABLE.signalAll();
                }
            } finally {
                LOCK.unlock();
            }
        }
    }
}
