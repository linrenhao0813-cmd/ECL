package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.launcher.VersionManager;
import com.ecl.launcher.VersionManager.VersionDownloadTarget;
import com.ecl.util.FileUtil;
import com.ecl.util.TestNetworkPolicy;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerJarDownloaderTest {
    private AutoCloseable loopbackDownloads;

    @BeforeEach
    void allowLoopbackDownloads() {
        loopbackDownloads = TestNetworkPolicy.allowLoopbackArtifactDownloads();
    }

    @AfterEach
    void restoreDownloadPolicy() throws Exception {
        loopbackDownloads.close();
    }

    @Test
    void resolvesInstalledVersionServerMetadataAndChannels(@TempDir Path tempDir) throws Exception {
        Field baseDir = ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path versionDir = tempDir.resolve("versions/1.21.4");
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve("1.21.4.json"), """
                    {
                      "id":"1.21.4",
                      "downloads":{"server":{
                        "url":"https://piston-data.mojang.com/v1/objects/hash/server.jar",
                        "sha1":"0123456789abcdef0123456789abcdef01234567",
                        "size":12345
                      }}
                    }
                    """);

            ServerJarDownloader.ServerArtifact artifact =
                    new ServerJarDownloader(new VersionManager()).resolve("1.21.4", null);

            assertEquals("1.21.4", artifact.versionId());
            assertEquals(12345, artifact.size());
            assertEquals(2, artifact.channels().size());
            assertFalse(artifact.channels().getFirst().mirror());
            assertEquals("BMCLAPI", artifact.channels().get(1).name());
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void serverJavaRequirementUsesExplicitMetadataIncludingYearBasedVersions() throws Exception {
        assertEquals(25, ServerJarDownloader.javaRequirement(JsonParser.parseString(
                "{\"id\":\"26.1\",\"javaVersion\":{\"majorVersion\":25}}").getAsJsonObject(), "26.1"));
        assertEquals(25, ServerJarDownloader.javaRequirement(JsonParser.parseString(
                "{\"id\":\"1.21.1\",\"javaVersion\":{\"majorVersion\":25}}").getAsJsonObject(), "1.21.1"));
        assertEquals(8, ServerJarDownloader.javaRequirement(JsonParser.parseString(
                "{\"id\":\"1.12.2\"}").getAsJsonObject(), "1.12.2"));
        assertThrows(java.io.IOException.class, () -> ServerJarDownloader.javaRequirement(JsonParser.parseString(
                "{\"id\":\"wrong\",\"javaVersion\":{\"majorVersion\":25}}").getAsJsonObject(), "26.1"));
        assertThrows(java.io.IOException.class, () -> ServerJarDownloader.javaRequirement(JsonParser.parseString(
                "{\"id\":\"26.1\",\"javaVersion\":{\"majorVersion\":0}}").getAsJsonObject(), "26.1"));
    }

    @Test
    void officialResolutionIgnoresAlteredLocalDownloadsAndPinsMetadataHash(@TempDir Path root) throws Exception {
        String official = "{\"id\":\"1.16.5\",\"javaVersion\":{\"majorVersion\":8},\"downloads\":{\"server\":{"
                + "\"url\":\"https://93.184.216.34/official.jar\",\"sha1\":\"" + "1".repeat(40) + "\",\"size\":123}}}";
        String modified = official.replace("93.184.216.34/official", "8.8.8.8/modified").replace("1".repeat(40), "2".repeat(40));
        Path local = root.resolve("1.16.5.json");
        Files.writeString(local, modified);
        String expectedMetadataSha1 = FileUtil.sha1(official.getBytes(StandardCharsets.UTF_8));
        VersionManager versions = new VersionManager() {
            @Override public VersionDownloadTarget resolveDownloadTarget(String id) {
                return new VersionDownloadTarget(id, id, "https://piston-meta.mojang.com/official.json", expectedMetadataSha1);
            }
            @Override public com.google.gson.JsonObject loadVersionJson(String id) throws java.io.IOException {
                return JsonParser.parseString(Files.readString(local)).getAsJsonObject();
            }
        };
        ServerJarDownloader downloader = new ServerJarDownloader(versions, (url, target) -> {
            assertEquals("https://piston-meta.mojang.com/official.json", url);
            Files.writeString(target, official);
        });
        assertEquals("https://93.184.216.34/official.jar", downloader.resolveOfficial("1.16.5", null).url());
        assertEquals("1".repeat(40), downloader.resolveOfficial("1.16.5", null).sha1());
        assertEquals(8, downloader.requiredJavaVersion("1.16.5"));
        ServerJarDownloader corrupt = new ServerJarDownloader(versions, (url, target) -> Files.writeString(target, modified));
        assertThrows(java.io.IOException.class, () -> corrupt.resolveOfficial("1.16.5", null));
        assertThrows(java.io.IOException.class, () -> corrupt.requiredJavaVersion("1.16.5"));
    }

    @Test
    void createsWindowsSafeSuggestedFileName() {
        assertEquals("minecraft_server.1.21_4_test.jar",
                ServerJarDownloader.suggestedFileName("1.21:4/test"));
    }

    @Test
    void failedVerificationDoesNotDeleteExistingTarget(@TempDir Path tempDir) throws Exception {
        byte[] response = "bad-server".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/server.jar", exchange -> {
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        Path target = tempDir.resolve("server.jar");
        Files.writeString(target, "old-server");
        String wrongHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest("expected-server".getBytes(StandardCharsets.UTF_8)));
        ServerJarDownloader.ServerArtifact artifact = new ServerJarDownloader.ServerArtifact(
                "1.21.4", "http://127.0.0.1:" + server.getAddress().getPort() + "/server.jar",
                wrongHash, response.length, java.util.List.of());

        try {
            assertThrows(java.io.IOException.class,
                    () -> new ServerJarDownloader(null).download(artifact, target.toFile(), null));
            assertEquals("old-server", Files.readString(target));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createsMissingTargetDirectoryBeforeDownloading(@TempDir Path tempDir) throws Exception {
        byte[] response = "verified-server".getBytes(StandardCharsets.UTF_8);
        String sha1 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(response));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/server.jar", exchange -> {
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        Path target = tempDir.resolve("new-directory/server.jar");
        ServerJarDownloader.ServerArtifact artifact = new ServerJarDownloader.ServerArtifact(
                "1.21.4", "http://127.0.0.1:" + server.getAddress().getPort() + "/server.jar",
                sha1, response.length, java.util.List.of());

        try {
            File downloaded = new ServerJarDownloader(null).download(artifact, target.toFile(), null);
            assertEquals(target.toFile(), downloaded);
            assertTrue(Files.isRegularFile(target));
            assertEquals("verified-server", Files.readString(target));
        } finally {
            server.stop(0);
        }
    }
}
