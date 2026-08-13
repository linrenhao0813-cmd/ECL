package com.ecl.util;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUtilTest {

    @Test
    void readsStandardHttpsProxyDefinition() {
        ProxySelector selector = HttpUtil.proxySelectorFor(
                "http://127.0.0.1:7897", "", "").orElseThrow();
        Proxy proxy = selector.select(URI.create("https://api.modrinth.com/v2/search")).getFirst();

        assertEquals(Proxy.Type.HTTP, proxy.type());
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(7897, address.getPort());
    }

    @Test
    void ignoresInvalidProxyDefinitions() {
        assertTrue(HttpUtil.proxySelectorFor("not a uri", "", "").isEmpty());
    }

    @Test
    void toleratesNullProxyEnvironmentVariables() {
        // Regression: System.getenv returns null for unset proxy variables and
        // List.of(...) rejects null elements, crashing startup with NPE.
        assertTrue(HttpUtil.proxySelectorFor(null, null, null).isEmpty());
        assertTrue(HttpUtil.proxySelectorFor("http://127.0.0.1:7897", null, null).isPresent());
        assertTrue(HttpUtil.proxySelectorFor(null, "http://127.0.0.1:7897", null).isPresent());
        assertTrue(HttpUtil.proxySelectorFor(null, null, "http://127.0.0.1:7897").isPresent());
    }
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getUsesUnifiedResponseHandlingForSuccessAndErrors() {
        server.createContext("/ok", exchange -> respond(exchange, 200, "response body"));
        server.createContext("/error", exchange -> respond(exchange, 422, "useful error"));

        assertEquals("response body", assertDoesNotThrowGet(baseUrl + "/ok"));
        IOException error = assertThrows(IOException.class, () -> HttpUtil.get(baseUrl + "/error"));
        assertTrue(error.getMessage().contains("HTTP 422"));
        assertTrue(error.getMessage().contains("useful error"));
    }

    @Test
    void preservesMixedLineEndingsAndContentAcrossReadBuffers() throws IOException {
        String body = "first\nsecond\r\n" + "x".repeat(5_000) + "\n";
        server.createContext("/multiline", exchange -> respond(exchange, 200, body));

        assertEquals(body, HttpUtil.get(baseUrl + "/multiline"));
    }

    @Test
    void binaryRequestsPreserveBytesAndEnforceTheMemoryLimit() throws IOException {
        byte[] body = new byte[]{0, 1, 2, 3, (byte) 255};
        server.createContext("/icon", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertArrayEquals(body, HttpUtil.getBytes(baseUrl + "/icon", body.length));
        IOException tooLarge = assertThrows(IOException.class,
                () -> HttpUtil.getBytes(baseUrl + "/icon", body.length - 1));
        assertTrue(tooLarge.getMessage().contains("exceeds"));
    }

    @Test
    void jsonRequestsAreCompactWhileJsonFilesRemainPrettyPrinted(@TempDir Path tempDir) throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/json", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "ok");
        });

        JsonObject json = new JsonObject();
        json.addProperty("name", "ECL");
        json.addProperty("enabled", true);
        assertEquals("ok", HttpUtil.postJson(baseUrl + "/json", json));
        assertFalse(requestBody.get().contains("\n"));

        File output = tempDir.resolve("settings.json").toFile();
        HttpUtil.writeJson(output, json);
        assertTrue(Files.readString(output.toPath()).contains(System.lineSeparator())
                || Files.readString(output.toPath()).contains("\n"));
    }

    @Test
    void downloadFileWithProgressReportsLifecycleAndWritesTarget(@TempDir Path tempDir) throws IOException {
        byte[] bytes = "download-body".getBytes(StandardCharsets.UTF_8);
        AtomicLong startedWith = new AtomicLong(-1);
        AtomicLong progressedTo = new AtomicLong(-1);
        AtomicReference<File> completedFile = new AtomicReference<>();
        server.createContext("/download", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        File target = tempDir.resolve("nested/file.txt").toFile();
        HttpUtil.downloadFileWithProgress(baseUrl + "/download", target, new HttpUtil.ProgressCallback() {
            @Override
            public void onStart(long total) {
                startedWith.set(total);
            }

            @Override
            public void onProgress(long downloaded, long total) {
                progressedTo.set(downloaded);
                startedWith.compareAndSet(-1, total);
            }

            @Override
            public void onComplete(File file) {
                completedFile.set(file);
            }
        });

        assertEquals(bytes.length, startedWith.get());
        assertEquals(bytes.length, progressedTo.get());
        assertEquals(target, completedFile.get());
        assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
    }

    @Test
    void resumesPartialDownloadWithRangeAndAtomicallyPromotesTarget(@TempDir Path tempDir) throws IOException {
        byte[] complete = "download-body".getBytes(StandardCharsets.UTF_8);
        byte[] remainder = "body".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> range = new AtomicReference<>();
        AtomicReference<String> ifRange = new AtomicReference<>();
        server.createContext("/resume", exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            ifRange.set(exchange.getRequestHeaders().getFirst("If-Range"));
            exchange.getResponseHeaders().add("Content-Range", "bytes 9-12/13");
            exchange.getResponseHeaders().add("ETag", "\"version-1\"");
            exchange.sendResponseHeaders(206, remainder.length);
            exchange.getResponseBody().write(remainder);
            exchange.close();
        });

        File target = tempDir.resolve("file.txt").toFile();
        Files.writeString(Path.of(target + ".part"), "download-", StandardCharsets.UTF_8);
        Files.writeString(Path.of(target + ".part.meta"), """
                {"source":"%s/resume","etag":"\\\"version-1\\\"","lastModified":""}
                """.formatted(baseUrl), StandardCharsets.UTF_8);
        HttpUtil.downloadFile(baseUrl + "/resume", target);

        assertEquals("bytes=9-", range.get());
        assertEquals("\"version-1\"", ifRange.get());
        assertArrayEquals(complete, Files.readAllBytes(target.toPath()));
        assertFalse(Files.exists(Path.of(target + ".part")));
    }

    @Test
    void invalidContentRangeIsDiscardedAndRetriedFromZero(@TempDir Path tempDir) throws IOException {
        byte[] complete = "download-body".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/bad-range", exchange -> {
            requests.incrementAndGet();
            if (exchange.getRequestHeaders().getFirst("Range") != null) {
                byte[] wrong = "body".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Range", "bytes 8-11/12");
                exchange.getResponseHeaders().add("ETag", "\"version-1\"");
                exchange.sendResponseHeaders(206, wrong.length);
                exchange.getResponseBody().write(wrong);
            } else {
                exchange.sendResponseHeaders(200, complete.length);
                exchange.getResponseBody().write(complete);
            }
            exchange.close();
        });

        File target = tempDir.resolve("file.txt").toFile();
        Files.writeString(Path.of(target + ".part"), "download-", StandardCharsets.UTF_8);
        Files.writeString(Path.of(target + ".part.meta"), """
                {"source":"%s/bad-range","etag":"\\\"version-1\\\"","lastModified":""}
                """.formatted(baseUrl), StandardCharsets.UTF_8);
        HttpUtil.downloadFile(baseUrl + "/bad-range", target);

        assertEquals(2, requests.get());
        assertArrayEquals(complete, Files.readAllBytes(target.toPath()));
    }

    @Test
    void metadataFreePartialIsDiscardedInsteadOfResumed(@TempDir Path tempDir) throws IOException {
        byte[] complete = "new-complete-content".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> range = new AtomicReference<>();
        server.createContext("/legacy-part", exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.sendResponseHeaders(200, complete.length);
            exchange.getResponseBody().write(complete);
            exchange.close();
        });
        File target = tempDir.resolve("legacy.txt").toFile();
        Files.writeString(Path.of(target + ".part"), "old-content", StandardCharsets.UTF_8);

        HttpUtil.downloadFile(baseUrl + "/legacy-part", target);

        assertTrue(range.get() == null);
        assertArrayEquals(complete, Files.readAllBytes(target.toPath()));
    }

    @Test
    void changedResumeValidatorForcesAFullRetry(@TempDir Path tempDir) throws IOException {
        byte[] complete = "new-download-body".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/changed", exchange -> {
            requests.incrementAndGet();
            if (exchange.getRequestHeaders().getFirst("Range") != null) {
                byte[] remainder = "body".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Range", "bytes 9-12/13");
                exchange.getResponseHeaders().add("ETag", "\"version-2\"");
                exchange.sendResponseHeaders(206, remainder.length);
                exchange.getResponseBody().write(remainder);
            } else {
                exchange.getResponseHeaders().add("ETag", "\"version-2\"");
                exchange.sendResponseHeaders(200, complete.length);
                exchange.getResponseBody().write(complete);
            }
            exchange.close();
        });
        File target = tempDir.resolve("changed.txt").toFile();
        Files.writeString(Path.of(target + ".part"), "download-", StandardCharsets.UTF_8);
        Files.writeString(Path.of(target + ".part.meta"), """
                {"source":"%s/changed","etag":"\\\"version-1\\\"","lastModified":""}
                """.formatted(baseUrl), StandardCharsets.UTF_8);

        HttpUtil.downloadFile(baseUrl + "/changed", target);

        assertEquals(2, requests.get());
        assertArrayEquals(complete, Files.readAllBytes(target.toPath()));
    }

    @Test
    void streamingLimitStopsDownloadAndDeletesPartialFiles(@TempDir Path tempDir) {
        byte[] oversized = new byte[64 * 1024];
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(oversized);
            } catch (IOException ignored) {
                // Expected when the client closes the response after crossing its byte budget.
            } finally {
                exchange.close();
            }
        });
        File target = tempDir.resolve("limited.bin").toFile();

        assertThrows(IOException.class, () -> HttpUtil.downloadFileWithProgress(
                baseUrl + "/oversized", target, null, null, 1024));
        assertFalse(target.exists());
        assertFalse(Path.of(target + ".part").toFile().exists());
        assertFalse(Path.of(target + ".part.meta").toFile().exists());
    }

    private static String assertDoesNotThrowGet(String url) {
        try {
            return HttpUtil.get(url);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
