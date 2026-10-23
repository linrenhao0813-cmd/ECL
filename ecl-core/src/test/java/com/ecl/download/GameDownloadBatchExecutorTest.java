package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.util.TestNetworkPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDownloadBatchExecutorTest {
    @TempDir
    Path temp;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Field baseDirField;
    private File previousBaseDir;
    private AutoCloseable loopbackDownloads;

    @BeforeEach
    void useIsolatedBaseDirectory() throws Exception {
        loopbackDownloads = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        // Logback keeps <baseDir>/logs/ecl.log open for the whole JVM, so the data root must stay
        // outside the JUnit temp directory that is deleted after each test.
        baseDirField.set(null, GameDownloaderTest.isolatedDataRoot().toFile());
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        try {
            baseDirField.set(null, previousBaseDir);
        } finally {
            executor.shutdownNow();
            loopbackDownloads.close();
        }
    }

    @Test
    void refusesArtifactTargetsInsideReservedStagingDirectory() throws Exception {
        Path target = temp.resolve(".ecl-downloads/managed.download");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "preserve");
        var task = new GameDownloadBatchExecutor.DownloadTask("http://127.0.0.1:1/artifact",
                target.toFile(), "a".repeat(40), 8, "资源文件", temp);

        IOException failure = assertThrows(IOException.class,
                () -> new GameDownloadBatchExecutor(executor, 1).downloadSingleVerified(task, null, null));

        assertTrue(failure.getMessage().contains("reserved staging directory"));
        assertEquals("preserve", Files.readString(target));
    }

    @Test
    void downloadDoesNotDeleteExistingFileWhoseNameMatchesOldStagingSuffix() throws Exception {
        Path target = temp.resolve("library.jar");
        Path other = Files.writeString(temp.resolve("library.jar.ecl-download"), "other-artifact");
        HttpServer server = createServer();
        try {
            var task = task("http://127.0.0.1:" + server.getAddress().getPort() + "/first.jar",
                    target, "a".repeat(40), 12);
            assertThrows(IOException.class,
                    () -> new GameDownloadBatchExecutor(executor, 1).downloadSingleVerified(task, null, null));
            assertEquals("other-artifact", Files.readString(other));

            byte[] payload = "downloaded".getBytes(StandardCharsets.UTF_8);
            server.createContext("/valid.jar", exchange -> {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            var validTask = task("http://127.0.0.1:" + server.getAddress().getPort() + "/valid.jar",
                    target, sha1(payload), payload.length);
            new GameDownloadBatchExecutor(executor, 1).downloadSingleVerified(validTask, null, null);
            assertEquals("other-artifact", Files.readString(other));
            assertArrayEquals(payload, Files.readAllBytes(target));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesInterruptedTransferAcrossExecutorInstancesAndPublishesOnlyVerifiedFile() throws Exception {
        byte[] payload = "verified-resumable-artifact".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        List<String> ranges = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resume.jar", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(range == null ? "" : range);
            exchange.getResponseHeaders().add("ETag", "\"version-1\"");
            if (requests.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload, 0, 8);
                exchange.getResponseBody().flush();
                exchange.close();
            } else {
                int offset = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
                if (range != null) {
                    assertEquals("\"version-1\"", exchange.getRequestHeaders().getFirst("If-Range"));
                    exchange.getResponseHeaders().add("Content-Range", "bytes " + offset + "-"
                            + (payload.length - 1) + "/" + payload.length);
                }
                exchange.sendResponseHeaders(range == null ? 200 : 206, payload.length - offset);
                exchange.getResponseBody().write(payload, offset, payload.length - offset);
                exchange.close();
            }
        });
        server.start();
        try {
            Path target = temp.resolve("resume.jar");
            Files.writeString(target, "original");
            var task = task("http://127.0.0.1:" + server.getAddress().getPort() + "/resume.jar",
                    target, sha1(payload), payload.length);
            assertThrows(IOException.class,
                    () -> new GameDownloadBatchExecutor(executor, 1).downloadSingleVerified(task, null, null));
            assertEquals("original", Files.readString(target));

            new GameDownloadBatchExecutor(executor, 1).downloadSingleVerified(task, null, null);

            assertEquals(List.of("", "bytes=8-"), ranges);
            assertArrayEquals(payload, Files.readAllBytes(target));
            try (var files = Files.walk(temp)) {
                assertEquals(List.of(target), files.filter(Files::isRegularFile).toList());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedChecksumPreservesOriginalAndDiscardsInvalidDownload() throws Exception {
        byte[] invalid = "invalid".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invalid.jar", exchange -> {
            exchange.sendResponseHeaders(200, invalid.length);
            exchange.getResponseBody().write(invalid);
            exchange.close();
        });
        server.start();
        try {
            Path target = temp.resolve("invalid.jar");
            Files.writeString(target, "original");
            var task = task("http://127.0.0.1:" + server.getAddress().getPort() + "/invalid.jar",
                    target, "a".repeat(40), invalid.length);
            assertThrows(IOException.class,
                    () -> new GameDownloadBatchExecutor(executor, 1).downloadSingleVerified(task, null, null));
            assertEquals("original", Files.readString(target));
            try (var files = Files.walk(temp)) {
                assertEquals(List.of(target), files.filter(Files::isRegularFile).toList());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsOneOfTwoTasksWithTheSameTargetExactlyOnce() throws Exception {
        byte[] payload = "shared-artifact".getBytes(StandardCharsets.UTF_8);
        String sha1 = sha1(payload);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/first.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            Path target = temp.resolve("objects/ab/shared.jar");
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
            GameDownloadBatchExecutor.DownloadTask first =
                    task(origin + "/first.jar", target, sha1, payload.length);
            GameDownloadBatchExecutor.DownloadTask second =
                    task(origin + "/first.jar", target, sha1, payload.length);

            new GameDownloadBatchExecutor(executor, 2).download(List.of(first, second),
                    "资源文件", listener(statuses));

            assertArrayEquals(payload, Files.readAllBytes(target));
            assertEquals(1, requests.get(), "the shared target must be downloaded once");
            assertTrue(statuses.stream().anyMatch(status -> status.contains("共 1 个文件")),
                    "progress must count one deduplicated file: " + statuses);
            assertEquals("下载资源文件: 1/1", statuses.getLast());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsDistinctTargetsDownloadable() throws Exception {
        byte[] payload = "distinct-artifact".getBytes(StandardCharsets.UTF_8);
        String sha1 = sha1(payload);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/artifact.jar", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            Path firstTarget = temp.resolve("objects/aa/first.jar");
            Path secondTarget = temp.resolve("objects/bb/second.jar");
            List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();

            new GameDownloadBatchExecutor(executor, 2).download(List.of(
                    task(origin + "/artifact.jar", firstTarget, sha1, payload.length),
                    task(origin + "/artifact.jar", secondTarget, sha1, payload.length)),
                    "资源文件", listener(statuses));

            assertArrayEquals(payload, Files.readAllBytes(firstTarget));
            assertArrayEquals(payload, Files.readAllBytes(secondTarget));
            assertEquals("下载资源文件: 2/2", statuses.getLast());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsConflictingDigestsForOneTarget() throws Exception {
        Path target = temp.resolve("objects/ab/artifact.jar");

        IOException failure = assertThrows(IOException.class, () -> downloadTogether(target,
                "a".repeat(40), "b".repeat(40)));

        assertConflictingTarget(failure, target);
    }

    @Test
    void rejectsConflictingSizesForOneTarget() throws Exception {
        Path target = temp.resolve("objects/ab/artifact.jar");

        IOException failure = assertThrows(IOException.class, () -> downloadTogether(target,
                "a".repeat(40), "a".repeat(40), 11, 12));

        assertConflictingTarget(failure, target);
    }

    private void downloadTogether(Path target, String firstSha1, String secondSha1)
            throws IOException {
        downloadTogether(target, firstSha1, secondSha1, 11, 11);
    }

    private void downloadTogether(Path target, String firstSha1, String secondSha1,
                                  long firstSize, long secondSize) throws IOException {
        HttpServer server = createServer();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            new GameDownloadBatchExecutor(executor, 2).download(List.of(
                    new GameDownloadBatchExecutor.DownloadTask(origin + "/first.jar",
                            target.toFile(), firstSha1, firstSize, "资源文件",
                            target.toFile().getParentFile().toPath()),
                    new GameDownloadBatchExecutor.DownloadTask(origin + "/second.jar",
                            target.toFile(), secondSha1, secondSize, "资源文件",
                            target.toFile().getParentFile().toPath())),
                    "资源文件", listener(new java.util.concurrent.CopyOnWriteArrayList<>()));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/first.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/second.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void assertConflictingTarget(IOException failure, Path target) {
        String message = failure.getMessage();
        assertTrue(message != null && message.contains("Conflicting"),
                "message must name a conflicting declaration: " + message);
        assertTrue(message.contains(target.toAbsolutePath().normalize().toString()),
                "message must name the target: " + message);
    }

    private static GameDownloadBatchExecutor.DownloadTask task(String url, Path target,
                                                               String sha1, long size) {
        return new GameDownloadBatchExecutor.DownloadTask(url, target.toFile(), sha1, size,
                "资源文件", target.toFile().getParentFile().toPath());
    }

    private static GameDownloader.DownloadListener listener(List<String> statuses) {
        return new GameDownloader.DownloadListener() {
            @Override public void onStatus(String message) {
                statuses.add(message);
            }

            @Override public void onProgress(long downloaded, long total) {
            }

            @Override public void onError(String message) {
            }

            @Override public void onComplete() {
            }
        };
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }
}
