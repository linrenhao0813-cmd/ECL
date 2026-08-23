package com.ecl.modrinth.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModFileDownloadServiceTest {
    @Test
    void rollsBackPartialProgressAfterAnIoFailure(@TempDir Path tempDirectory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] partial = "part".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] complete = "complete".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(200, partial.length + 10L);
            exchange.getResponseBody().write(partial);
            exchange.close();
        });
        server.createContext("/complete", exchange -> respond(exchange, complete));
        server.start();
        var executor = Executors.newSingleThreadExecutor();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ModDownloadRequest broken = new ModDownloadRequest(
                    URI.create(baseUrl + "/broken"), "broken.jar",
                    tempDirectory.resolve("broken.jar"), Map.of(), partial.length + 10L);
            ModDownloadRequest successful = new ModDownloadRequest(
                    URI.create(baseUrl + "/complete"), "complete.jar",
                    tempDirectory.resolve("complete.jar"), Map.of(), complete.length);
            AtomicLong successfulOverall = new AtomicLong(-1);
            var result = new ModFileDownloadService(executor, new HashVerifier()).downloadAll(
                    List.of(broken, successful), progress -> {
                        if (successful.fileName().equals(progress.fileName())) {
                            successfulOverall.set(progress.overallDownloaded());
                        }
                    });

            assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals(complete.length, successfulOverall.get());
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void cancellingBatchCancelsQueuedDownloadTask(@TempDir Path tempDirectory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            Path temporaryFile = tempDirectory.resolve("queued.jar");
            ModDownloadRequest request = new ModDownloadRequest(
                    URI.create("https://example.invalid/queued.jar"),
                    "queued.jar",
                    temporaryFile,
                    Map.of(),
                    0);
            var result = new ModFileDownloadService(executor, new HashVerifier())
                    .downloadAll(java.util.List.of(request), null);

            assertTrue(result.cancel(true));
            releaseBlocker.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(temporaryFile));
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNonHttpDownloadSchemes(@TempDir Path tempDirectory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            ModDownloadRequest request = new ModDownloadRequest(
                    URI.create("file:///etc/passwd"), "unsafe.jar",
                    tempDirectory.resolve("unsafe.jar"), Map.of(), 0);
            var result = new ModFileDownloadService(executor, new HashVerifier())
                    .downloadAll(List.of(request), null);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException
                    || failure.getCause().getCause() instanceof IOException);
            assertFalse(Files.exists(request.temporaryFile()));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
