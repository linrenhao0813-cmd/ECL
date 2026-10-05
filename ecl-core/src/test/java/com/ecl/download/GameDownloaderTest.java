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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsVersionMetadataWhenSha1DoesNotMatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/version.json", exchange -> {
            byte[] metadata = "{\"downloads\":{},\"libraries\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        server.start();
        try (GameDownloader downloader = new GameDownloader(1)) {
            String versionUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/version.json";
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> downloader.downloadVersionAsync(
                            "bad-meta", versionUrl, "0".repeat(40)).get());
            assertTrue(failure.getCause() instanceof IOException);
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

    @Test
    void clientSizeMismatchRemovesJarAndStopsBeforeDependencies() throws Exception {
        byte[] client = "short-client".getBytes(StandardCharsets.UTF_8);
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(client));
        Set<String> requests = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.jar", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, client.length);
            exchange.getResponseBody().write(client);
            exchange.close();
        });
        server.createContext("/version.json", exchange -> {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] metadata = ("""
                    {"downloads":{"client":{"url":"%s/client.jar","sha1":"%s","size":%d}},
                     "libraries":[{"downloads":{"artifact":{"path":"unused.jar",
                         "url":"%s/unused.jar","sha1":"%s","size":%d}}}]}
                    """).formatted(root, sha1, client.length + 1, root, sha1, client.length)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        server.createContext("/unused.jar", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, client.length);
            exchange.getResponseBody().write(client);
            exchange.close();
        });
        server.start();
        AtomicBoolean completed = new AtomicBoolean();
        try (GameDownloader downloader = new GameDownloader(1)) {
            downloader.setListener(new DownloadListenerAdapter() {
                @Override
                public void onComplete() {
                    completed.set(true);
                }
            });
            String versionUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/version.json";

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> downloader.downloadVersionAsync("short-client", versionUrl).get());

            assertTrue(failure.getCause() instanceof IOException);
            assertEquals("Minecraft client size does not match metadata", failure.getCause().getMessage());
            Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve("short-client");
            assertFalse(Files.exists(versionDirectory.resolve("short-client.jar")));
            assertFalse(Files.exists(versionDirectory.resolve(ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER)));
            assertEquals(Set.of("/client.jar"), requests);
            assertFalse(completed.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reusesVerifiedArtifactsAndNativesWhileRespectingLibraryRules() throws Exception {
        byte[] library = "verified-library".getBytes(StandardCharsets.UTF_8);
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(library));
        Path librariesDirectory = ECLConfig.getLibrariesDir().toPath();
        Files.createDirectories(librariesDirectory.resolve("example"));
        Files.write(librariesDirectory.resolve("example/artifact.jar"), library);
        Files.write(librariesDirectory.resolve("example/native.jar"), library);
        try (GameDownloader downloader = new GameDownloader(2)) {
            String root = "http://127.0.0.1:1";
            String metadata = ("""
                    {"libraries":[
                      {"name":"example:artifact:1","downloads":{
                        "artifact":{"path":"example/artifact.jar","url":"%s/artifact.jar","sha1":"%s","size":%d},
                        "classifiers":{"natives-windows":{
                          "path":"example/native.jar","url":"%s/native.jar","sha1":"%s","size":%d}}}},
                      {"name":"example:skipped:1","rules":[{"action":"disallow"}],
                        "downloads":{"artifact":{}}},
                      {"name":"example:explicit-empty:1","url":"%s/repo/","downloads":{}}
                    ]}
                    """).formatted(root, sha1, library.length, root, sha1, library.length, root);
            Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve("libraries-only");
            Files.createDirectories(versionDirectory);
            Files.writeString(versionDirectory.resolve("libraries-only.json"), metadata);

            downloader.downloadLibrariesForVersion("libraries-only", null);

            assertArrayEquals(library, Files.readAllBytes(librariesDirectory.resolve("example/artifact.jar")));
            assertArrayEquals(library, Files.readAllBytes(librariesDirectory.resolve("example/native.jar")));
            assertFalse(Files.exists(librariesDirectory.resolve("example/explicit-empty")));
        }
    }

    @Test
    void rejectsHttpMavenFallbackOnlyWhenDownloadsAreAbsent() throws Exception {
        Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve("maven-library");
        Files.createDirectories(versionDirectory);
        Path metadataFile = versionDirectory.resolve("maven-library.json");
        Files.writeString(metadataFile, """
                {"libraries":[{"name":"example:maven:1","url":"http://127.0.0.1:1/repo/","downloads":{}}]}
                """);
        try (GameDownloader downloader = new GameDownloader(1)) {
            downloader.downloadLibrariesForVersion("maven-library", null);

            Files.writeString(metadataFile, """
                    {"libraries":[{"name":"example:maven:1","url":"http://127.0.0.1:1/repo/"}]}
                    """);
            IOException failure = assertThrows(IOException.class,
                    () -> downloader.downloadLibrariesForVersion("maven-library", null));

            assertEquals("Maven library example:maven:1 URL must use HTTPS", failure.getMessage());
            assertFalse(Files.exists(ECLConfig.getLibrariesDir().toPath().resolve("example/maven/1/maven-1.jar")));
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
