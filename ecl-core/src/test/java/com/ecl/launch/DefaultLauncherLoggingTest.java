package com.ecl.launch;

import com.ecl.auth.OfflineAuth;
import com.ecl.game.VersionRepository;
import com.ecl.util.TestNetworkPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLauncherLoggingTest {

    private static final byte[] LOG_CONFIG = "<Configuration/>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private HttpServer server;
    private AutoCloseable loopback;
    private String origin;
    private Path versions;
    private Path assets;
    private Path game;
    private LaunchEnvironment environment;

    @BeforeEach
    void setUp() throws Exception {
        loopback = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client-1.7.2.xml", exchange -> {
            exchange.sendResponseHeaders(200, LOG_CONFIG.length);
            exchange.getResponseBody().write(LOG_CONFIG);
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        versions = Files.createDirectories(temp.resolve("versions"));
        Path libraries = Files.createDirectories(temp.resolve("libraries"));
        assets = Files.createDirectories(temp.resolve("assets"));
        game = temp.resolve("game");
        environment = new LaunchEnvironment(versions.toFile(), libraries.toFile(),
                assets.toFile(), "ECL", "test");
        writeLoggingVersion();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            server.stop(0);
        } finally {
            loopback.close();
        }
    }

    @Test
    void previewFailsWithMissingFilesWhenTheLogConfigIsAbsentButDownloadsNothing() throws Exception {
        DefaultLauncher launcher = new DefaultLauncher(
                new VersionRepository(versions.toFile()), environment);

        LaunchException error = assertThrows(LaunchException.class,
                () -> launcher.preview(options()));

        assertEquals(LaunchException.Kind.MISSING_FILES, error.kind());
        assertFalse(Files.exists(assets.resolve("log_configs/client-1.7.2.xml")),
                "preview must not download the log config");
    }

    @Test
    void prepareDownloadsTheLogConfigBeforeBuildingTheCommand() throws Exception {
        DefaultLauncher launcher = new DefaultLauncher(
                new VersionRepository(versions.toFile()), environment);

        LaunchCommand command = launcher.prepare(options());

        Path target = assets.resolve("log_configs/client-1.7.2.xml");
        assertTrue(Files.isRegularFile(target), "prepare() must fetch the missing log config");
        List<String> args = command.arguments();
        assertTrue(args.contains("-Dlog4j.configurationFile=" + target.toAbsolutePath()),
                args.toString());
    }

    private LaunchOptions options() {
        return LaunchOptions.builder()
                .versionId("1.7.2")
                .auth(new OfflineAuth("Player"))
                .gameDirectory(game.toFile())
                .environment(environment)
                .build();
    }

    @Test
    void prepareMaterializesResourcesForAnAlreadyInstalledCustomRunDirectory() throws Exception {
        byte[] sound = "sound".getBytes(StandardCharsets.UTF_8);
        String hash = com.ecl.util.FileUtil.sha1(sound);
        Path object = assets.resolve("objects/" + hash.substring(0, 2) + "/" + hash);
        Files.createDirectories(object.getParent());
        Files.write(object, sound);
        byte[] index = ("{\"map_to_resources\":true,\"objects\":{\"sound.ogg\":{\"hash\":\""
                + hash + "\",\"size\":" + sound.length + "}}}").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(assets.resolve("indexes"));
        Files.write(assets.resolve("indexes/legacy.json"), index);
        Path profile = versions.resolve("1.7.2/1.7.2.json");
        var json = com.google.gson.JsonParser.parseString(Files.readString(profile)).getAsJsonObject();
        json.add("assetIndex", com.google.gson.JsonParser.parseString("{\"id\":\"legacy\",\"sha1\":\""
                + com.ecl.util.FileUtil.sha1(index) + "\"}"));
        json.addProperty("minecraftArguments", "--gameAssets ${game_assets}");
        Files.writeString(profile, json.toString());
        Files.createDirectories(assets.resolve("log_configs"));
        Files.write(assets.resolve("log_configs/client-1.7.2.xml"), LOG_CONFIG);
        DefaultLauncher launcher = new DefaultLauncher(new VersionRepository(versions.toFile()), environment);

        launcher.preview(options());
        assertFalse(Files.exists(game.resolve("resources/sound.ogg")));
        LaunchCommand command = launcher.prepare(options());

        assertEquals("sound", Files.readString(game.resolve("resources/sound.ogg")));
        assertEquals(game.resolve("resources").toString(),
                command.arguments().get(command.arguments().indexOf("--gameAssets") + 1));
    }

    private void writeLoggingVersion() throws Exception {
        Path directory = Files.createDirectories(versions.resolve("1.7.2"));
        Files.write(directory.resolve("1.7.2.jar"), new byte[]{1, 2, 3});
        String sha1 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(LOG_CONFIG));
        Files.writeString(directory.resolve("1.7.2.json"), """
                {"id":"1.7.2","mainClass":"net.minecraft.client.main.Main",
                 "javaVersion":{"majorVersion":21},
                 "downloads":{"client":{"url":"https://example/client.jar","path":"1.7.2/1.7.2.jar"}},
                 "logging":{"client":{"argument":"-Dlog4j.configurationFile=${path}",
                   "file":{"id":"client-1.7.2.xml","sha1":"%s","size":%d,"url":"%s/client-1.7.2.xml"}}}}
                """.formatted(sha1, LOG_CONFIG.length, origin), StandardCharsets.UTF_8);
    }
}
