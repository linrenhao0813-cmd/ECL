package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.launcher.VersionManager;
import com.ecl.launch.GameProcessMarker;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.operation.InstanceOperationCoordinator;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameInstallationRepairServiceTest {
    @TempDir Path temp;
    private Field baseDirField;
    private File previousBaseDir;
    private AutoCloseable loopback;

    @BeforeEach
    void configureIsolatedData() throws Exception {
        org.slf4j.LoggerFactory.getILoggerFactory();
        loopback = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
        ECLConfig.ensureDirs();
    }

    @AfterEach
    void restoreSettings() throws Exception {
        try {
            baseDirField.set(null, previousBaseDir);
        } finally {
            loopback.close();
        }
    }

    @Test
    void repairsMissingVerifiedClientAndAssetsWithoutTouchingUserFiles() throws Exception {
        byte[] client = "repaired-client".getBytes(StandardCharsets.UTF_8);
        byte[] index = "{\"objects\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] logging = "patched logging config".getBytes(StandardCharsets.UTF_8);
        String clientSha1 = sha1(client);
        String indexSha1 = sha1(index);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var unexpectedRequests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            unexpectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/client.jar", exchange -> {
            exchange.sendResponseHeaders(200, client.length);
            exchange.getResponseBody().write(client);
            exchange.close();
        });
        server.createContext("/index.json", exchange -> {
            exchange.sendResponseHeaders(200, index.length);
            exchange.getResponseBody().write(index);
            exchange.close();
        });
        server.createContext("/logging.xml", exchange -> {
            exchange.sendResponseHeaders(200, logging.length);
            exchange.getResponseBody().write(logging);
            exchange.close();
        });
        String metadata = "{\"id\":\"repair-test\",\"downloads\":{\"client\":{"
                + "\"url\":\"" + origin + "/client.jar\",\"sha1\":\"" + clientSha1
                + "\",\"size\":" + client.length + "}},\"assetIndex\":{\"id\":\"test\","
                + "\"url\":\"" + origin + "/index.json\",\"sha1\":\"" + indexSha1
                + "\",\"size\":" + index.length + "},\"libraries\":[],"
                + "\"logging\":{\"client\":{\"argument\":\"-Dlog4j.configurationFile=${path}\",\"file\":{"
                + "\"id\":\"client.xml\",\"sha1\":\"" + sha1(logging) + "\",\"size\":" + logging.length
                + ",\"url\":\"" + origin + "/logging.xml\"}}}}";
        var document = com.google.gson.JsonParser.parseString(metadata).getAsJsonObject();
        document.getAsJsonArray("libraries").add(com.google.gson.JsonParser.parseString("""
                {"name":"net.fabricmc:fabric-loader:1","url":"%s/"}
                """.formatted(origin)));
        byte[] dependency = "verified-library".getBytes(StandardCharsets.UTF_8);
        server.createContext("/dependency.jar", exchange -> {
            exchange.sendResponseHeaders(200, dependency.length);
            exchange.getResponseBody().write(dependency);
            exchange.close();
        });
        document.getAsJsonArray("libraries").add(com.google.gson.JsonParser.parseString("""
                {"name":"org.example:library:1","downloads":{"artifact":{"path":"example/library.jar",
                 "url":"%s/dependency.jar","sha1":"%s","size":%d}}}
                """.formatted(origin, sha1(dependency), dependency.length)));
        metadata = document.toString();
        byte[] metadataBytes = metadata.getBytes(StandardCharsets.UTF_8);
        String metadataSha1 = sha1(metadataBytes);
        server.createContext("/version.json", exchange -> {
            exchange.sendResponseHeaders(200, metadataBytes.length);
            exchange.getResponseBody().write(metadataBytes);
            exchange.close();
        });
        server.start();
        Path versions = temp.resolve("custom-versions");
        Path libraries = temp.resolve("custom-libraries");
        Path assets = temp.resolve("custom-assets");
        Path versionDirectory = versions.resolve("repair-test");
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve("repair-test.json"), metadata);
        Path gameRoot = temp.resolve("minecraft");
        Path instanceRoot = gameRoot.resolve("versions/repair-test");
        Path mod = instanceRoot.resolve("mods/user.jar");
        Path save = instanceRoot.resolve("saves/world/level.dat");
        Path config = instanceRoot.resolve("config/user.cfg");
        Files.createDirectories(mod.getParent());
        Files.createDirectories(save.getParent());
        Files.createDirectories(config.getParent());
        Files.writeString(mod, "user mod");
        Files.writeString(save, "user save");
        Files.writeString(config, "user config");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "repair-test", instanceRoot);
        GameInstallationVerifier.Report before = new GameInstallationVerifier(versions, libraries, assets).inspect("repair-test");
        GameInstallationRepairService service = new GameInstallationRepairService(
                new InstanceOperationCoordinator(), ignored -> false);
        VersionManager.VersionDownloadTarget target = new VersionManager.VersionDownloadTarget(
                "repair-test", "repair-test", origin + "/version.json", metadataSha1);

        try (GameDownloader downloader = new GameDownloader(1)) {
            Path otherInstance = gameRoot.resolve("versions/other-instance");
            GameProcessMarker.record(otherInstance, ProcessHandle.current());
            try {
                assertThrows(java.io.IOException.class, () -> service.repair(instance, before, target,
                        downloader, null, () -> false,
                        versions, libraries, assets, gameRoot));
            } finally {
                GameProcessMarker.clear(otherInstance, ProcessHandle.current());
            }
            GameInstallationRepairService.Result result = service.repair(instance, before, target,
                    downloader, null, () -> false,
                    versions, libraries, assets, gameRoot);

            assertEquals(1, result.unverifiable().size());
            assertTrue(result.failed().isEmpty(), result.failed().toString());
            assertEquals("verified-library", Files.readString(libraries.resolve("example/library.jar")));
            assertTrue(!Files.exists(libraries.resolve("net/fabricmc/fabric-loader/1/fabric-loader-1.jar")));
            assertTrue(!Files.exists(ECLConfig.getVersionsDir().toPath().resolve("repair-test")));
            assertEquals("patched logging config", Files.readString(
                    assets.resolve("log_configs/client.xml")));
            assertTrue(result.repaired().size() >= 2);
            assertEquals("user mod", Files.readString(mod));
            assertEquals("user save", Files.readString(save));
            assertEquals("user config", Files.readString(config));
            Path metadataFile = versionDirectory.resolve("repair-test.json");
            Files.delete(metadataFile);
            var missingMetadata = service.inspect("repair-test", versions, libraries, assets);
            var wrongDigest = new VersionManager.VersionDownloadTarget(
                    "repair-test", "repair-test", origin + "/version.json", "0".repeat(40));
            var rejected = service.repair(instance, missingMetadata, wrongDigest, downloader, null, () -> false,
                    versions, libraries, assets, gameRoot);
            assertEquals(1, rejected.failed().size());
            assertTrue(!Files.exists(metadataFile));
            var restored = service.repair(instance, rejected.after(), target, downloader, null, () -> false,
                    versions, libraries, assets, gameRoot);
            assertTrue(restored.failed().isEmpty());
            assertEquals(0, unexpectedRequests.get(), "repair must not fetch undeclared Maven files or .sha1 sidecars");
            assertEquals(document, com.google.gson.JsonParser.parseString(Files.readString(metadataFile)));

        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRepairStillReturnsAfterReportAndKeepsCompletionMarker() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] client = "valid-client".getBytes(StandardCharsets.UTF_8);
        byte[] metadata = ("{\"downloads\":{\"client\":{\"url\":\"" + origin
                + "/good.jar\",\"sha1\":\"" + sha1(client) + "\",\"size\":" + client.length + "}},\"libraries\":["
                + "{\"name\":\"example:broken:1\",\"downloads\":{\"artifact\":{\"path\":\"broken.jar\",\"url\":\"" + origin
                + "/broken.jar\",\"sha1\":\"" + "a".repeat(40) + "\",\"size\":3}}}]}")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/good.jar", exchange -> {
            exchange.sendResponseHeaders(200, client.length);
            exchange.getResponseBody().write(client);
            exchange.close();
        });
        server.createContext("/metadata", exchange -> {
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        server.createContext("/broken.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        Path versions = ECLConfig.getVersionsDir().toPath();
        Path libraries = ECLConfig.getLibrariesDir().toPath();
        Path assets = ECLConfig.getAssetsDir().toPath();
        Path profile = versions.resolve("broken");
        Files.createDirectories(profile);
        Files.write(profile.resolve("broken.json"), metadata);
        Path marker = Files.writeString(profile.resolve(ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER), "complete");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "broken", profile);
        var service = new GameInstallationRepairService(new InstanceOperationCoordinator(), ignored -> false);
        var before = service.inspect("broken", versions, libraries, assets);
        var target = new VersionManager.VersionDownloadTarget("broken", "broken", origin + "/metadata", sha1(metadata));
        try (GameDownloader downloader = new GameDownloader(1)) {
            var result = service.repair(instance, before, target, downloader, null, () -> false,
                    versions, libraries, assets, temp.resolve("minecraft"));
            assertTrue(result.after().checks().stream().anyMatch(c -> c.category() == GameInstallationVerifier.Category.LIBRARY
                    && c.status() == GameInstallationVerifier.Status.MISSING));
            assertEquals(1, result.failed().size());
            assertTrue(result.repaired().stream().anyMatch(c -> c.category() == GameInstallationVerifier.Category.CLIENT));
            assertEquals("valid-client", Files.readString(profile.resolve("broken.jar")));
            assertEquals("complete", Files.readString(marker));
        } finally {
            server.stop(0);
        }
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private record TestInstance(UUID instanceId, String profileId,
                                Path gameDirectory) implements ModInstanceContext {
        @Override public String minecraftVersion() { return profileId; }
        @Override public ModLoader loader() { return ModLoader.NONE; }
        @Override public Path modsDirectory() { return gameDirectory.resolve("mods"); }
    }
}
