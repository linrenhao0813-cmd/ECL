package com.ecl.pack;

import com.ecl.ECLConfig;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.util.TestNetworkPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPackServiceTest {
    @TempDir
    Path temp;
    private Field baseDirField;
    private File previousBaseDir;
    private AutoCloseable loopbackDownloads;

    @BeforeEach
    void allowLoopbackDownloads() throws Exception {
        loopbackDownloads = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
    }

    @AfterEach
    void restoreDownloadPolicy() throws Exception {
        try {
            baseDirField.set(null, previousBaseDir);
        } finally {
            loopbackDownloads.close();
        }
    }

    @Test
    void eclPackRoundTripIsPreviewableAndTransactional() throws Exception {
        Path instance = Files.createDirectories(temp.resolve("source"));
        Files.createDirectories(instance.resolve("mods"));
        Files.writeString(instance.resolve("mods/example.jar"), "mod");
        DefaultPackService service = new DefaultPackService();
        Path archive = service.exportInstance(instance, "1.21.1", PackFormat.ECL,
                temp.resolve("pack.zip"));

        PackPreview preview = service.preview(archive);
        assertEquals(PackFormat.ECL, preview.format());
        assertEquals("1.21.1", preview.minecraftVersion());
        Path instances = Files.createDirectories(temp.resolve("instances"));
        PackImportResult result = service.importPack(archive, instances, "Imported");

        assertTrue(Files.isRegularFile(result.instanceDirectory().resolve("mods/example.jar")));
        assertFalse(Files.exists(instances.resolve(".ecl-pack-")));
        assertThrows(Exception.class, () -> service.importPack(archive, instances, "Imported"));
    }

    @Test
    void allFormatsRoundTrip() throws Exception {
        DefaultPackService service = new DefaultPackService();
        for (PackFormat format : PackFormat.values()) {
            Path instance = Files.createDirectories(temp.resolve("source-" + format));
            Path mods = Files.createDirectories(instance.resolve("mods"));
            Files.writeString(mods.resolve("example.jar"), "mod");
            Path archive = service.exportInstance(instance, "1.21.1", format,
                    temp.resolve(format + ".zip"));
            assertEquals(format, service.preview(archive).format());
            Path resultRoot = Files.createDirectories(temp.resolve("instances-" + format));
            PackImportResult imported = service.importPack(archive, resultRoot, "Imported");
            assertTrue(Files.isRegularFile(imported.instanceDirectory().resolve("mods/example.jar")));
        }
    }

    @Test
    void sharedRuntimeExportIncludesPayloadAndOnlySelectedInstanceMetadata() throws Exception {
        Path game = Files.createDirectories(temp.resolve("shared-game"));
        Path instance = Files.createDirectories(game.resolve("versions/selected"));
        writeFile(instance.resolve("selected.json"), "{\"id\":\"selected\"}");
        writeFile(instance.resolve("selected.jar"), "client");
        writeFile(instance.resolve(".ecl/config/launch-profile.json"), "{\"maxMemoryMb\":2048}");
        writeFile(instance.resolve(".ecl/operations/operation.json"), "operation");
        writeFile(game.resolve("versions/other/other.json"), "other-instance");
        writeFile(game.resolve("saves/world/level.dat"), "shared-world");
        writeFile(game.resolve("mods/shared.jar"), "shared-mod");
        writeFile(game.resolve("resourcepacks/pack.zip"), "resource-pack");
        writeFile(game.resolve("shaderpacks/shader.zip"), "shader-pack");
        writeFile(game.resolve("config/mod.toml"), "mod-config");
        writeFile(game.resolve("options.txt"), "game-options");
        writeFile(game.resolve("launcher_accounts.json"), "secret-access-token");
        writeFile(game.resolve("launcher_profiles.json"), "launcher-profiles");
        writeFile(game.resolve("accounts.json"), "account-token");
        writeFile(game.resolve("libraries/library.jar"), "shared-library");
        writeFile(game.resolve("assets/object"), "shared-asset");
        DefaultPackService service = new DefaultPackService();

        Path archive = service.exportInstance(instance, game, "1.21.1", PackFormat.ECL,
                game.resolve("saves/export.zip"));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Set<String> names = zip.stream().map(ZipEntry::getName).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("ecl-pack.json", "instance/selected.json", "instance/selected.jar",
                    "instance/.ecl/config/launch-profile.json", "instance/.ecl/config/instance-game-settings.json",
                    "instance/saves/world/level.dat", "instance/mods/shared.jar", "instance/resourcepacks/pack.zip",
                    "instance/shaderpacks/shader.zip", "instance/config/mod.toml", "instance/options.txt"), names);
            assertEquals("shared-world", zipText(zip, "instance/saves/world/level.dat"));
            assertEquals("{\"maxMemoryMb\":2048}", zipText(zip, "instance/.ecl/config/launch-profile.json"));
        }
        PackImportResult imported = service.importPack(archive, temp.resolve("shared-imports"), "Imported");
        assertEquals("shared-mod", Files.readString(imported.instanceDirectory().resolve("mods/shared.jar")));
        assertEquals(InstanceGameSettings.isolated(), new InstanceGameSettingsStore().load(imported.instanceDirectory()));
    }

    @Test
    void customRuntimeRoundTripUsesExportedPayloadAndPreservesInstanceLaunchSettings() throws Exception {
        Path instance = Files.createDirectories(temp.resolve("custom-game/versions/selected"));
        Path runtime = Files.createDirectories(temp.resolve("custom-run"));
        writeFile(instance.resolve("selected.json"), "{\"id\":\"selected\",\"mainClass\":\"Main\"}");
        writeFile(instance.resolve(".ecl/config/launch-profile.json"), "{\"maxMemoryMb\":4096}");
        writeFile(instance.resolve("mods/stale.jar"), "old-payload");
        InstanceGameSettingsStore settings = new InstanceGameSettingsStore();
        settings.save(instance, InstanceGameSettings.custom(runtime));
        writeFile(runtime.resolve("mods/current.jar"), "current-mod");
        writeFile(runtime.resolve("saves/world/level.dat"), "custom-world");
        writeFile(runtime.resolve("kubejs/startup_scripts/setup.js"), "startup-script");
        writeFile(runtime.resolve(".ecl/config/launch-profile.json"), "wrong-runtime-profile");
        writeFile(runtime.resolve("launcher_accounts.json"), "private-token");
        DefaultPackService service = new DefaultPackService();

        Path archive = service.exportInstance(instance, runtime, "1.21.1", PackFormat.ECL,
                runtime.resolve("export.zip"));
        PackImportResult imported = service.importPack(archive, temp.resolve("custom-imports/versions"), "Imported");

        Path result = imported.instanceDirectory();
        assertEquals("current-mod", Files.readString(result.resolve("mods/current.jar")));
        assertEquals("custom-world", Files.readString(result.resolve("saves/world/level.dat")));
        assertEquals("startup-script", Files.readString(result.resolve("kubejs/startup_scripts/setup.js")));
        assertEquals("{\"maxMemoryMb\":4096}", Files.readString(result.resolve(".ecl/config/launch-profile.json")));
        assertFalse(Files.exists(result.resolve("mods/stale.jar")));
        assertFalse(Files.exists(result.resolve("launcher_accounts.json")));
        assertFalse(Files.exists(result.resolve("export.zip")));
        assertEquals(InstanceGameSettings.custom(runtime), settings.load(instance));
        assertEquals(InstanceGameSettings.isolated(), settings.load(result));
        // Give the imported version its directory name before resolving its launch target.
        writeFile(result.resolve("Imported.json"), Files.readString(result.resolve("selected.json")));
        DefaultGameRepository repository = new DefaultGameRepository(result.getParent(), result.getParent().getParent());
        assertEquals(result, repository.runDirectory("Imported"));
    }

    @Test
    void overlappingRuntimeDirectoriesDoNotDuplicateMetadataOrArchiveTheOutput() throws Exception {
        DefaultPackService service = new DefaultPackService();
        for (boolean nested : new boolean[] {false, true}) {
            Path instance = Files.createDirectories(temp.resolve("overlap-" + nested));
            Path runtime = nested ? Files.createDirectories(instance.resolve("runtime")) : instance;
            writeFile(instance.resolve(".ecl/config/launch-profile.json"), "profile");
            writeFile(runtime.resolve("mods/example.jar"), "mod");
            Path archive = runtime.resolve("mods/export.zip");
            writeFile(archive, "previous-export");
            service.exportInstance(instance, runtime, "1.21.1", PackFormat.ECL, archive);

            try (ZipFile zip = new ZipFile(archive.toFile())) {
                assertEquals("mod", zipText(zip, "instance/mods/example.jar"));
                assertEquals("profile", zipText(zip, "instance/.ecl/config/launch-profile.json"));
                assertEquals(1, zip.stream().filter(entry -> entry.getName().endsWith("launch-profile.json")).count());
                assertFalse(zip.stream().anyMatch(entry -> entry.getName().endsWith("export.zip")));
            }
        }
    }

    @Test
    void selectedVersionMetadataFallsBackToEclCacheWithoutExportingOtherProfiles() throws Exception {
        String id = "cache..selected";
        Path instance = Files.createDirectories(temp.resolve("game/versions").resolve(id));
        writeFile(instance.resolve("mods/example.jar"), "mod");
        Path cache = ECLConfig.getVersionsDir().toPath();
        writeFile(cache.resolve(id).resolve(id + ".json"), "{\"id\":\"cache..selected\"}");
        writeFile(cache.resolve(id).resolve(id + ".jar"), "cached-client");
        writeFile(cache.resolve("other/other.json"), "other-profile");
        DefaultPackService service = new DefaultPackService();
        Path archive = temp.resolve("cached.zip");

        service.exportInstance(instance, instance, "1.21.1", PackFormat.ECL, archive);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals("{\"id\":\"cache..selected\"}", zipText(zip, "instance/" + id + ".json"));
            assertEquals("cached-client", zipText(zip, "instance/" + id + ".jar"));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("other")));
        }

        writeFile(instance.resolve(id + ".json"), "local-profile");
        writeFile(instance.resolve(id + ".jar"), "local-client");
        service.exportInstance(instance, instance, "1.21.1", PackFormat.ECL, archive);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals("local-profile", zipText(zip, "instance/" + id + ".json"));
            assertEquals("local-client", zipText(zip, "instance/" + id + ".jar"));
        }
    }

    private static void writeFile(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String zipText(ZipFile zip, String name) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void twoHundredModPackRoundTripStaysFast() throws Exception {
        DefaultPackService service = new DefaultPackService();
        Path instance = Files.createDirectories(temp.resolve("large-source"));
        Path mods = Files.createDirectories(instance.resolve("mods"));
        for (int index = 0; index < 200; index++) {
            Files.writeString(mods.resolve("example-" + index + ".jar"), "mod-" + index);
        }

        assertTimeout(Duration.ofSeconds(10), () -> {
            Path archive = service.exportInstance(instance, "1.21.1", PackFormat.MRPACK,
                    temp.resolve("large.mrpack"));
            Path resultRoot = Files.createDirectories(temp.resolve("large-instances"));
            PackImportResult imported = service.importPack(archive, resultRoot, "Imported");
            assertTrue(Files.isRegularFile(imported.instanceDirectory().resolve("mods/example-199.jar")));
        });
    }

    @Test
    void mrpackImportDownloadsAndVerifiesManifestFiles() throws Exception {
        byte[] mod = "real-mod-content".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/example.jar", exchange -> {
            exchange.sendResponseHeaders(200, mod.length);
            exchange.getResponseBody().write(mod);
            exchange.close();
        });
        server.start();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(mod));
            String index = """
                    {"formatVersion":1,"game":"minecraft","name":"Remote Pack","versionId":"1",
                     "dependencies":{"minecraft":"1.21.1"},
                     "files":[{"path":"mods/example.jar","fileSize":%d,"hashes":{"sha512":"%s"},
                     "downloads":["http://127.0.0.1:%d/example.jar"]}]}
                    """.formatted(mod.length, hash, server.getAddress().getPort());
            Path archive = temp.resolve("remote.mrpack");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("modrinth.index.json"));
                zip.write(index.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            Path root = Files.createDirectories(temp.resolve("remote-instances"));
            PackImportResult result = new DefaultPackService(
                    new com.ecl.modrinth.pack.MrpackInstaller(java.util.Set.of("127.0.0.1")))
                    .importPack(archive, root, "Imported");

            assertEquals("real-mod-content",
                    Files.readString(result.instanceDirectory().resolve("mods/example.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedMrpackRemoteFileIsRejectedBeforeNetworkAccess() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oversized.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String index = """
                    {"formatVersion":1,"game":"minecraft","name":"Oversized","versionId":"1",
                     "dependencies":{"minecraft":"1.21.1"},
                     "files":[{"path":"mods/oversized.jar","fileSize":2147483649,
                     "hashes":{"sha512":"00"},
                     "downloads":["http://127.0.0.1:%d/oversized.jar"]}]}
                    """.formatted(server.getAddress().getPort());
            Path archive = temp.resolve("oversized-remote.mrpack");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("modrinth.index.json"));
                zip.write(index.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            Path root = Files.createDirectories(temp.resolve("oversized-remote-instances"));
            assertThrows(java.io.IOException.class,
                    () -> new DefaultPackService().importPack(archive, root, "Imported"));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unrecognizedArchiveIsRejectedWithoutCreatingAnInstance() throws Exception {
        Path archive = temp.resolve("unknown.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{\"name\":\"Pack\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path root = Files.createDirectories(temp.resolve("unknown-instances"));

        assertThrows(java.io.IOException.class,
                () -> new DefaultPackService().importPack(archive, root, "Imported"));
        assertFalse(Files.exists(root.resolve("Imported")));
    }

    @Test
    void oversizedManifestIsRejectedBeforeParsing() throws Exception {
        Path archive = temp.resolve("oversized.mrpack");
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        int state = 0x13579bdf;
        for (int i = 0; i < oversized.length; i++) {
            state = state * 1664525 + 1013904223;
            oversized[i] = (byte) (state >>> 24);
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(oversized);
            zip.closeEntry();
        }

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> new DefaultPackService().preview(archive));
        assertTrue(failure.getMessage().contains("manifest"));
    }
}
