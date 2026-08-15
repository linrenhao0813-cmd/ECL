package com.ecl.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CurseForgeDownloaderTest {
    @Test
    void convertsManifestAndOverridesToMrpack(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("pack.zip");
        String manifest = """
                {"manifestType":"minecraftModpack","manifestVersion":1,
                 "name":"Test Pack","version":"2.0","overrides":"overrides",
                 "minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0"}]},
                 "files":[]}
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("overrides/config/example.txt"));
            zip.write("ok".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        File converted = new CurseForgeDownloader(() -> "unused")
                .convertModpackToMrpack(archive.toFile());
        try (ZipFile zip = new ZipFile(converted, StandardCharsets.UTF_8)) {
            assertNotNull(zip.getEntry("overrides/config/example.txt"));
            JsonObject index = JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry("modrinth.index.json")).readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("1.20.1", index.getAsJsonObject("dependencies")
                    .get("minecraft").getAsString());
            assertEquals("47.2.0", index.getAsJsonObject("dependencies")
                    .get("forge").getAsString());
        } finally {
            Files.deleteIfExists(converted.toPath());
        }
    }
}
