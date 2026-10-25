package com.ecl.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainControllerTest {
    @Test
    void closedControllerRejectsWorkRatherThanReturningAnUnfinishedFuture() {
        MainController controller = new MainController();
        controller.close();
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> controller.runAsync("closed", () -> { }));
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> controller.supplyAsync("closed", () -> true));
    }

    @Test
    void blockedBackgroundTasksNeverRunOnTheSubmittingThread() throws Exception {
        MainController controller = new MainController();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(300);
        Thread submitting = Thread.currentThread();
        AtomicBoolean callerRan = new AtomicBoolean();
        try {
            for (int index = 0; index < 300; index++) {
                controller.runAsync("blocked", () -> {
                    callerRan.compareAndSet(false, Thread.currentThread() == submitting);
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(2, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertFalse(callerRan.get());
        } finally {
            release.countDown();
            controller.close();
        }
    }

    @Test
    void closeInterruptsManagedBackgroundTasks() throws Exception {
        MainController controller = new MainController();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        controller.runAsync("test-background-task", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        controller.close();
        for (int i = 0; i < 20 && !interrupted.get(); i++) {
            Thread.sleep(25);
        }
        assertTrue(interrupted.get());
    }
}
