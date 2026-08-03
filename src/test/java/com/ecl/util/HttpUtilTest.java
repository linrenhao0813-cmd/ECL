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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUtilTest {
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
