package com.ecl.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/** Shared thread factories for launcher-owned background executors. */
public final class ThreadFactories {
    private ThreadFactories() {
    }

    public static ThreadFactory daemon(String prefix) {
        String threadPrefix = Objects.requireNonNull(prefix, "prefix");
        if (threadPrefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return Thread.ofPlatform().daemon(true).name(threadPrefix + "-", 1).factory();
    }
}
