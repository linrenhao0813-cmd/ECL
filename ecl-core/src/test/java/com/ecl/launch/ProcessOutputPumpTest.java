package com.ecl.launch;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessOutputPumpTest {

    @Test
    void forwardsLinesToListeners() throws Exception {
        ProcessOutputPump pump = feed("a\nb\nc\n");
        CountDownLatch allLines = new CountDownLatch(3);
        List<String> received = new CopyOnWriteArrayList<>();
        pump.addListener(line -> {
            received.add(line);
            allLines.countDown();
        });
        pump.start();
        assertTrue(allLines.await(5, TimeUnit.SECONDS));
        pump.close();

        assertEquals(List.of("a", "b", "c"), received);
    }

    @Test
    void failingListenerDoesNotBlockOthers() throws Exception {
        ProcessOutputPump pump = feed("x\n");
        CountDownLatch survivor = new CountDownLatch(1);
        pump.addListener(line -> {
            throw new IllegalStateException("boom");
        });
        pump.addListener(line -> survivor.countDown());
        pump.start();
        assertTrue(survivor.await(5, TimeUnit.SECONDS));
        pump.close();
    }

    @Test
    void capturedTextKeepsOnlyTheTail() {
        ProcessOutputPump pump = new ProcessOutputPump(
                input("01\n02\n03\n04\n05\n"), 12);
        pump.start();
        waitUntil(() -> !pump.capturedText().isBlank());
        pump.close();

        String captured = pump.capturedText();
        assertTrue(captured.endsWith("05") || captured.endsWith("05\n"),
                "tail should keep the last line, got: " + captured);
        assertEquals(0, pump.capturedText().length() - captured.length());
        assertTrue(pump.capturedText().contains("05"));
    }

    @Test
    void startIsIdempotent() {
        ProcessOutputPump pump = feed("a\n");
        AtomicBoolean ranOnce = new AtomicBoolean();
        pump.addListener(line -> ranOnce.set(true));
        pump.start();
        pump.start();
        pump.close();
        assertTrue(ranOnce.get());
    }

    private static InputStream input(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ProcessOutputPump feed(String content) {
        return new ProcessOutputPump(input(content), 80_000);
    }

    private static void waitUntil(ThrowingBoolean condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("condition not met in time");
    }

    private interface ThrowingBoolean {
        boolean get();
    }
}