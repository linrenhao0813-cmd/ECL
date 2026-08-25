package com.ecl.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadConcurrencyGateTest {
    @AfterEach
    void restoreDefaultLimit() {
        DownloadConcurrencyGate.setLimit(2);
    }

    @Test
    void limitsConcurrentTransfersAcrossIndependentExecutors() throws Exception {
        DownloadConcurrencyGate.setLimit(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstPairEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                tasks.add(executor.submit(() -> {
                    try (DownloadConcurrencyGate.Permit ignored = DownloadConcurrencyGate.acquire()) {
                        int current = active.incrementAndGet();
                        try {
                            maximum.accumulateAndGet(current, Math::max);
                            firstPairEntered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } finally {
                            active.decrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            assertTrue(firstPairEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
            release.countDown();
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, active.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
