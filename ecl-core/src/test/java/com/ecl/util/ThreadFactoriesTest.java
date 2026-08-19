package com.ecl.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadFactoriesTest {
    @Test
    void createsNumberedDaemonThreads() {
        ThreadFactory factory = ThreadFactories.daemon("ecl-worker");

        Thread first = factory.newThread(() -> { });
        Thread second = factory.newThread(() -> { });

        assertTrue(first.isDaemon());
        assertTrue(second.isDaemon());
        assertEquals("ecl-worker-1", first.getName());
        assertEquals("ecl-worker-2", second.getName());
    }
}
