package com.ecl.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionManagerTest {
    @Test
    void filtersManifestByCategory() throws Exception {
        JsonObject manifest = JsonParser.parseString("""
                {"versions":[
                  {"id":"1.21","type":"release","releaseTime":"2024-06-13T00:00:00Z"},
                  {"id":"24w14potato","type":"snapshot","releaseTime":"2024-04-01T00:00:00Z"},
                  {"id":"24w20a","type":"snapshot","releaseTime":"2024-05-15T00:00:00Z"},
                  {"id":"custom-infinite-build","type":"old_alpha","releaseTime":"2024-05-16T00:00:00Z"},
                  {"id":"custom-april-build","type":"snapshot","releaseTime":"2026-04-01T00:00:00Z"}
                ]}
                """).getAsJsonObject();
        VersionManager manager = new VersionManager();
        Field field = VersionManager.class.getDeclaredField("manifest");
        field.setAccessible(true);
        field.set(manager, manifest);

        assertEquals(List.of("1.21"), manager.getReleaseVersions());
        assertEquals(List.of("24w14potato", "custom-april-build"), manager.getAprilFoolsVersions());
        assertEquals(List.of("24w14potato", "24w20a", "custom-april-build"), manager.getPreviewVersions());
    }

    @Test
    void inheritedVersionUsesTheParentClientJar(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path parent = tempDir.resolve("versions/base");
            Path child = tempDir.resolve("versions/child");
            Files.createDirectories(parent);
            Files.createDirectories(child);
            Files.writeString(parent.resolve("base.json"), "{\"id\":\"base\"}");
            Files.write(parent.resolve("base.jar"), new byte[]{1});
            Files.writeString(child.resolve("child.json"), "{\"id\":\"child\",\"inheritsFrom\":\"base\"}");

            assertEquals(true, new VersionManager().isVersionDownloaded("child"));
        } finally {
            baseDir.set(null, previous);
        }
    }
}
