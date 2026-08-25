package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MrpackFileInstallerTest {
    @TempDir
    Path tempDir;

    private Field baseDirField;
    private File previousBaseDir;

    @Test
    void defaultPolicyTrustsModrinthAndCurseForgeCdnHosts() {
        assertTrue(MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS
                .contains("cdn.modrinth.com"));
        assertTrue(MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS
                .contains("mediafilez.forgecdn.net"));
        assertTrue(MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS
                .contains("edge.forgecdn.net"));
    }

    @BeforeEach
    void useTemporaryBaseDirectory() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, tempDir.resolve("ecl").toFile());
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void downloadsIndexedFileVerifiesSha512AndReportsStatus() throws Exception {
        byte[] content = "mod-content".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer("/mod.jar", content);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject index = indexWithFile("mods/mod.jar", baseUrl + "/mod.jar",
                    content.length, sha512(content));
            Path instance = tempDir.resolve("instance");
            List<String> statuses = new ArrayList<>();

            int count = installFromLocalServer(index, instance, statuses::add);

            assertEquals(1, count);
            assertArrayEquals(content, Files.readAllBytes(instance.resolve("mods/mod.jar")));
            assertTrue(statuses.stream().anyMatch(status -> status.contains("mods/mod.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifiesSha1WhenSha512IsAbsent() throws Exception {
        byte[] content = "sha1-content".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer("/sha1.jar", content);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject item = new JsonObject();
            item.addProperty("path", "mods/sha1.jar");
            item.addProperty("fileSize", content.length);
            JsonArray downloads = new JsonArray();
            downloads.add(baseUrl + "/sha1.jar");
            item.add("downloads", downloads);
            JsonObject hashes = new JsonObject();
            hashes.addProperty("sha1", sha1(content));
            item.add("hashes", hashes);
            JsonObject index = indexWithFiles(item);
            Path instance = tempDir.resolve("instance");

            int count = installFromLocalServer(index, instance, message -> { });

            assertEquals(1, count);
            assertArrayEquals(content, Files.readAllBytes(instance.resolve("mods/sha1.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsFilesMarkedUnsupportedForClient() throws Exception {
        JsonObject item = new JsonObject();
        item.addProperty("path", "mods/server-only.jar");
        item.addProperty("fileSize", 10);
        JsonObject env = new JsonObject();
        env.addProperty("client", "unsupported");
        item.add("env", env);
        JsonObject index = indexWithFiles(item);

        Path instance = tempDir.resolve("instance");
        int count = MrpackFileInstaller.installIndexedFiles(index, instance, message -> { });

        assertEquals(0, count);
        assertFalse(Files.exists(instance.resolve("mods/server-only.jar")));
    }

    @Test
    void failsWhenDownloadedContentDoesNotMatchDeclaredHash() throws Exception {
        byte[] content = "mod-content".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer("/mod.jar", content);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject index = indexWithFile("mods/mod.jar", baseUrl + "/mod.jar",
                    content.length, sha512("different".getBytes(StandardCharsets.UTF_8)));
            Path instance = tempDir.resolve("instance");

            IOException error = assertThrows(IOException.class,
                    () -> installFromLocalServer(index, instance, message -> { }));

            assertTrue(error.getMessage().contains("整合包文件下载失败"));
            assertTrue(error.getCause().getMessage().contains("整合包文件校验失败"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWhenDownloadedSizeDoesNotMatchManifest() throws Exception {
        byte[] content = "mod-content".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer("/mod.jar", content);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject index = indexWithFile("mods/mod.jar", baseUrl + "/mod.jar",
                    content.length + 10, null);
            Path instance = tempDir.resolve("instance");

            IOException error = assertThrows(IOException.class,
                    () -> installFromLocalServer(index, instance, message -> { }));

            assertTrue(error.getMessage().contains("整合包文件下载失败"));
            assertTrue(error.getCause().getMessage()
                    .contains("MRPACK file size does not match its manifest"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWhenFileDeclaresNoDownloadUrl() throws Exception {
        JsonObject item = new JsonObject();
        item.addProperty("path", "mods/no-url.jar");
        item.addProperty("fileSize", 10);
        item.add("downloads", new JsonArray());
        JsonObject index = indexWithFiles(item);

        Path instance = tempDir.resolve("instance");
        IOException error = assertThrows(IOException.class,
                () -> MrpackFileInstaller.installIndexedFiles(index, instance, message -> { }));

        assertTrue(error.getMessage().contains("整合包文件没有下载地址"));
    }

    @Test
    void failsWhenEveryDownloadUrlFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject index = indexWithFile("mods/missing.jar", baseUrl + "/missing.jar", 100, null);
            Path instance = tempDir.resolve("instance");

            IOException error = assertThrows(IOException.class,
                    () -> installFromLocalServer(index, instance, message -> { }));

            assertTrue(error.getMessage().contains("整合包文件下载失败"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String path, byte[] content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void rejectsFileSchemeAndUntrustedHostsBeforeDownload() {
        JsonObject fileUrl = indexWithFile("mods/evil.jar", "file:///etc/passwd", 1, null);
        JsonObject untrusted = indexWithFile(
                "mods/evil.jar", "https://example.invalid/evil.jar", 1, null);
        Path instance = tempDir.resolve("instance");

        IOException fileError = assertThrows(IOException.class,
                () -> MrpackFileInstaller.installIndexedFiles(
                        fileUrl, instance, message -> { }));
        IOException hostError = assertThrows(IOException.class,
                () -> MrpackFileInstaller.installIndexedFiles(
                        untrusted, instance, message -> { }));

        assertTrue(fileError.getCause().getMessage().contains("not trusted"));
        assertTrue(hostError.getCause().getMessage().contains("not trusted"));
    }

    private static int installFromLocalServer(JsonObject index, Path instance,
                                              MrpackInstaller.Listener listener)
            throws IOException {
        return MrpackFileInstaller.installIndexedFiles(
                index, instance, listener, Set.of("127.0.0.1"));
    }

    private static JsonObject indexWithFile(String path, String url, long size, String sha512) {
        JsonObject item = new JsonObject();
        item.addProperty("path", path);
        item.addProperty("fileSize", size);
        JsonArray downloads = new JsonArray();
        downloads.add(url);
        item.add("downloads", downloads);
        if (sha512 != null) {
            JsonObject hashes = new JsonObject();
            hashes.addProperty("sha512", sha512);
            item.add("hashes", hashes);
        }
        return indexWithFiles(item);
    }

    private static JsonObject indexWithFiles(JsonObject item) {
        JsonArray files = new JsonArray();
        files.add(item);
        JsonObject index = new JsonObject();
        index.add("files", files);
        return index;
    }

    private static String sha512(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
    }

    private static String sha1(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
    }
}
