package com.ecl.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainControllerTest {
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
