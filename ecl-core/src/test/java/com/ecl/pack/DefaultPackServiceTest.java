package com.ecl.pack;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPackServiceTest {
    @TempDir
    Path temp;

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
    void twoHundredModPackRoundTripStaysFast() {
        assertTimeout(Duration.ofSeconds(5), () -> {
            DefaultPackService service = new DefaultPackService();
            Path instance = Files.createDirectories(temp.resolve("large-source"));
            Path mods = Files.createDirectories(instance.resolve("mods"));
            for (int index = 0; index < 200; index++) {
                Files.writeString(mods.resolve("example-" + index + ".jar"), "mod-" + index);
            }
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
    void curseForgeRemoteEntriesFailInsteadOfProducingIncompleteInstance() throws Exception {
        Path archive = temp.resolve("curseforge.zip");
        String manifest = """
                {"manifestType":"minecraftModpack","manifestVersion":1,"name":"Pack",
                 "minecraft":{"version":"1.20.1","modLoaders":[]},
                 "files":[{"projectID":1,"fileID":2}],"overrides":"overrides"}
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("overrides/options.txt"));
            zip.write("options".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path root = Files.createDirectories(temp.resolve("curse-instances"));

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
