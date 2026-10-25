package com.ecl.game;

import com.ecl.util.TestNetworkPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
class ClientLoggingConfigTest {

    private static final byte[] LOG4J_CONFIG = "<Configuration/>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private AutoCloseable loopback;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        loopback = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client-1.7.2.xml", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, LOG4J_CONFIG.length);
            exchange.getResponseBody().write(LOG4J_CONFIG);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        try {
            server.stop(0);
        } finally {
            loopback.close();
        }
    }

    private ClientLoggingConfig config(String url) throws Exception {
        return ClientLoggingConfig.parse(com.google.gson.JsonParser.parseString("""
                {"argument":"-Dlog4j.configurationFile=${path}",
                 "file":{"id":"client-1.7.2.xml","sha1":"%s","size":%d,"url":"%s"}}
                """.formatted(sha1(LOG4J_CONFIG), LOG4J_CONFIG.length, url)).getAsJsonObject());
    }

    @Test
    void downloadsTheLogConfigIntoTheFixedAssetsLogConfigsDirectory() throws Exception {
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        ClientLoggingConfig logging = config("http://127.0.0.1:" + server.getAddress().getPort()
                + "/client-1.7.2.xml");

        Path downloaded = logging.ensureDownloaded(assets.toFile()).toPath();

        assertEquals(assets.resolve("log_configs/client-1.7.2.xml"), downloaded);
        assertArrayEquals(LOG4J_CONFIG, Files.readAllBytes(downloaded));
        assertFalse(Files.exists(assets.resolve("log_configs/client-1.7.2.xml.part")));
    }

    @Test
    void skipsTheDownloadWhenTheFileAlreadyMatchesSizeAndSha1() throws Exception {
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        ClientLoggingConfig logging = config("http://127.0.0.1:" + server.getAddress().getPort()
                + "/client-1.7.2.xml");
        Files.createDirectories(assets.resolve("log_configs"));
        Files.write(assets.resolve("log_configs/client-1.7.2.xml"), LOG4J_CONFIG);

        Path target = logging.ensureDownloaded(assets.toFile()).toPath();

        assertEquals(0, requests.get(), "an intact file must not be downloaded again");
        assertArrayEquals(LOG4J_CONFIG, Files.readAllBytes(target));
    }

    @Test
    void replacesACorruptFileAfterVerification() throws Exception {
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        ClientLoggingConfig logging = config("http://127.0.0.1:" + server.getAddress().getPort()
                + "/client-1.7.2.xml");
        Files.createDirectories(assets.resolve("log_configs"));
        Files.writeString(assets.resolve("log_configs/client-1.7.2.xml"), "tampered");
        Path target = assets.resolve("log_configs/client-1.7.2.xml");

        logging.ensureDownloaded(assets.toFile());

        assertEquals(1, requests.get());
        assertArrayEquals(LOG4J_CONFIG, Files.readAllBytes(target));
    }

    @Test
    void keepsTheExistingFileWhenTheReplacementDownloadFails() throws Exception {
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        Files.createDirectories(assets.resolve("log_configs"));
        Files.writeString(assets.resolve("log_configs/client-1.7.2.xml"), "wrong content");
        ClientLoggingConfig logging = ClientLoggingConfig.parse(com.google.gson.JsonParser.parseString("""
                {"argument":"-Dlog4j.configurationFile=${path}",
                 "file":{"id":"client-1.7.2.xml","sha1":"%s","size":%d,
                   "url":"https://127.0.0.1:1/client-1.7.2.xml"}}
                """.formatted(sha1("unrelated".getBytes(StandardCharsets.UTF_8)), LOG4J_CONFIG.length))
                .getAsJsonObject());
        Path target = assets.resolve("log_configs/client-1.7.2.xml");

        assertThrows(IOException.class, () -> logging.ensureDownloaded(assets.toFile()));

        assertEquals("wrong content", Files.readString(target), "a failed download must not delete the file");
    }

    @Test
    void pathStaysInsideTheAssetsLogConfigsDirectory() throws Exception {
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        ClientLoggingConfig logging = config("https://launcher.example/client-1.7.2.xml");

        assertEquals(assets.resolve("log_configs").resolve("client-1.7.2.xml").toAbsolutePath(),
                logging.targetFile(assets.toFile()).toPath().toAbsolutePath());
        assertEquals("log_configs/client-1.7.2.xml", logging.relativePath());
    }

    @Test
    void rejectsLoggingDeclarationsAboveSixteenMiB() throws Exception {
        var json = com.google.gson.JsonParser.parseString("""
                {"argument":"-Dlog4j.configurationFile=${path}",
                 "file":{"id":"client.xml","sha1":"%s","size":16777217,"url":"https://example.com/client.xml"}}
                """.formatted(sha1(LOG4J_CONFIG))).getAsJsonObject();
        assertThrows(IOException.class, () -> ClientLoggingConfig.parse(json));
        assertEquals(0, requests.get());
    }

    @Test
    void failedDownloadCleansRandomPartialAndValidatorFiles() throws Exception {
        server.createContext("/broken.xml", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.sendResponseHeaders(200, LOG4J_CONFIG.length);
            exchange.getResponseBody().write(LOG4J_CONFIG, 0, 4);
            exchange.close();
        });
        ClientLoggingConfig logging = config("http://127.0.0.1:" + server.getAddress().getPort() + "/broken.xml");
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        assertThrows(IOException.class, () -> logging.ensureDownloaded(assets.toFile()));
        try (var paths = Files.walk(assets)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void cancellationCleansRandomPartialAndValidatorFiles() throws Exception {
        var bodyStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseBody = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/cancel.xml", exchange -> {
            try {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                exchange.sendResponseHeaders(200, LOG4J_CONFIG.length);
                exchange.getResponseBody().write(LOG4J_CONFIG, 0, 4);
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                releaseBody.await(10, java.util.concurrent.TimeUnit.SECONDS);
                exchange.getResponseBody().write(LOG4J_CONFIG, 4, LOG4J_CONFIG.length - 4);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        ClientLoggingConfig logging = config("http://127.0.0.1:" + server.getAddress().getPort() + "/cancel.xml");
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                logging.ensureDownloaded(assets.toFile());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(bodyStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean partialWritten = false;
            while (!partialWritten && System.nanoTime() < deadline) {
                try (var paths = Files.walk(assets)) {
                    partialWritten = paths.anyMatch(path -> path.toString().endsWith(".part") && path.toFile().length() > 0);
                }
                if (!partialWritten) Thread.sleep(10);
            }
            org.junit.jupiter.api.Assertions.assertTrue(partialWritten);
            worker.interrupt();
        } finally {
            releaseBody.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        org.junit.jupiter.api.Assertions.assertNotNull(failure.get());
        try (var paths = Files.walk(assets)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }
}
