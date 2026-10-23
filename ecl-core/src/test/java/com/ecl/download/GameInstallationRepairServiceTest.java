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
        byte[] metadataBytes = metadata.getBytes(StandardCharsets.UTF_8);
        String metadataSha1 = sha1(metadataBytes);
        server.createContext("/version.json", exchange -> {
            exchange.sendResponseHeaders(200, metadataBytes.length);
            exchange.getResponseBody().write(metadataBytes);
            exchange.close();
        });
        server.start();
        Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve("repair-test");
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
        GameInstallationVerifier.Report before = GameInstallationVerifier.configured().inspect("repair-test");
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
                        ECLConfig.getVersionsDir().toPath(), ECLConfig.getLibrariesDir().toPath(),
                        ECLConfig.getAssetsDir().toPath(), gameRoot));
            } finally {
                GameProcessMarker.clear(otherInstance, ProcessHandle.current());
            }
            GameInstallationRepairService.Result result = service.repair(instance, before, target,
                    downloader, null, () -> false,
                    ECLConfig.getVersionsDir().toPath(), ECLConfig.getLibrariesDir().toPath(),
                    ECLConfig.getAssetsDir().toPath(), gameRoot);

            assertTrue(result.after().complete());
            assertEquals("patched logging config", Files.readString(
                    ECLConfig.getAssetsDir().toPath().resolve("log_configs/client.xml")));
            assertTrue(result.repaired().size() >= 2);
            assertEquals("user mod", Files.readString(mod));
            assertEquals("user save", Files.readString(save));
            assertEquals("user config", Files.readString(config));
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
