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

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }
}
