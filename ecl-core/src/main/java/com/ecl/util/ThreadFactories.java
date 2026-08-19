package com.ecl.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared thread factories for launcher-owned background executors. */
public final class ThreadFactories {
    private ThreadFactories() {
    }

    public static ThreadFactory daemon(String prefix) {
        String threadPrefix = Objects.requireNonNull(prefix, "prefix");
        if (threadPrefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    threadPrefix + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
