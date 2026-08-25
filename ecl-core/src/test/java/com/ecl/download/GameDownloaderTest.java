package com.ecl.download;

import com.ecl.ECLConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameDownloaderTest {
    @TempDir
    Path temp;
    private Field baseDirField;
    private File previousBaseDir;

    @BeforeEach
    void useIsolatedBaseDirectory() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void asynchronousDownloadFutureFailsWhenPreparationFails() {
        try (GameDownloader downloader = new GameDownloader(1)) {
            Future<?> future = downloader.downloadVersionAsync("../unsafe", "not-a-url");

            ExecutionException failure = assertThrows(ExecutionException.class, future::get);
            assertTrue(failure.getCause() != null);
        }
    }

    @Test
    void downloadsAndVerifiesMinimalClientVersion() throws Exception {
        byte[] client = "verified-client".getBytes(StandardCharsets.UTF_8);
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(client));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.jar", exchange -> {
            exchange.sendResponseHeaders(200, client.length);
            exchange.getResponseBody().write(client);
            exchange.close();
        });
        server.createContext("/version.json", exchange -> {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] metadata = ("{\"downloads\":{\"client\":{\"url\":\"" + root
                    + "/client.jar\",\"sha1\":\"" + sha1 + "\",\"size\":"
                    + client.length + "}},\"libraries\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        server.start();
        AtomicBoolean completed = new AtomicBoolean();
        try (GameDownloader downloader = new GameDownloader(2)) {
            downloader.setListener(new DownloadListenerAdapter() {
                @Override
                public void onComplete() {
                    completed.set(true);
                }
            });
            String versionUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/version.json";

            downloader.downloadVersionAsync("test-version", versionUrl).get();

            Path installed = ECLConfig.getVersionsDir().toPath()
                    .resolve("test-version/test-version.jar");
            assertArrayEquals(client, Files.readAllBytes(installed));
            assertTrue(completed.get());
        } finally {
            server.stop(0);
        }
    }

    private abstract static class DownloadListenerAdapter implements GameDownloader.DownloadListener {
        @Override
        public void onStatus(String message) {
        }

        @Override
        public void onProgress(long downloaded, long total) {
        }

        @Override
        public void onError(String message) {
        }
    }
}
