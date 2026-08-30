package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.util.TestNetworkPolicy;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameDownloaderTest {
    @TempDir
    Path temp;
    private Field baseDirField;
    private File previousBaseDir;
    private AutoCloseable loopbackDownloads;

    @BeforeEach
    void useIsolatedBaseDirectory() throws Exception {
        loopbackDownloads = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        try {
            baseDirField.set(null, previousBaseDir);
        } finally {
            loopbackDownloads.close();
        }
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
            assertTrue(Files.isRegularFile(installed.resolveSibling(
                    ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER)));
            assertTrue(completed.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedDependencyDownloadDoesNotMarkVersionComplete() throws Exception {
        byte[] client = "verified-client".getBytes(StandardCharsets.UTF_8);
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(client));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.jar", exchange -> {
            exchange.sendResponseHeaders(200, client.length);
            exchange.getResponseBody().write(client);
            exchange.close();
        });
        server.createContext("/missing.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/version.json", exchange -> {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] metadata = ("{\"downloads\":{\"client\":{\"url\":\"" + root
                    + "/client.jar\",\"sha1\":\"" + sha1 + "\",\"size\":"
                    + client.length + "}},\"libraries\":[{\"name\":\"example:missing:1\","
                    + "\"downloads\":{\"artifact\":{\"url\":\"" + root
                    + "/missing.jar\",\"path\":\"example/missing/1/missing-1.jar\","
                    + "\"sha1\":\"" + "0".repeat(40) + "\",\"size\":7}}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        server.start();
        try (GameDownloader downloader = new GameDownloader(1)) {
            String versionUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/version.json";
            assertThrows(ExecutionException.class,
                    () -> downloader.downloadVersionAsync("incomplete-version", versionUrl).get());

            Path versionDirectory = ECLConfig.getVersionsDir().toPath()
                    .resolve("incomplete-version");
            assertTrue(Files.exists(versionDirectory.resolve("incomplete-version.jar")));
            assertFalse(Files.exists(versionDirectory.resolve(
                    ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER)));
            assertFalse(new com.ecl.launcher.VersionManager()
                    .isVersionDownloaded("incomplete-version"));
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
