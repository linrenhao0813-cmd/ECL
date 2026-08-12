package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MrpackInstallerTest {
    @TempDir
    Path tempDir;

    private Field baseDirField;
    private File previousBaseDir;

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
    void installsOverridesAndCreatesLaunchableInheritedProfile() throws Exception {
        Path archive = tempDir.resolve("example.mrpack");
        String index = """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "name": "Example Pack",
                  "versionId": "2.0",
                  "files": [],
                  "dependencies": {"minecraft": "1.21.4"}
                }
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(index.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("overrides/config/example.txt"));
            zip.write("enabled=true".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("client-overrides/options.txt"));
            zip.write("fov:0.5".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Path gameRoot = tempDir.resolve("game");
        MrpackInstaller.InstallResult result = new MrpackInstaller()
                .install(archive.toFile(), gameRoot.toFile(), "Preferred Name", null);

        assertEquals("1.21.4", result.minecraftVersion());
        assertEquals("", result.loader());
        assertEquals("enabled=true",
                Files.readString(result.instanceDirectory().resolve("config/example.txt")));
        assertEquals("fov:0.5",
                Files.readString(result.instanceDirectory().resolve("options.txt")));
        Path profileJson = ECLConfig.getVersionsDir().toPath()
                .resolve(result.profileId()).resolve(result.profileId() + ".json");
        JsonObject profile = JsonParser.parseString(Files.readString(profileJson)).getAsJsonObject();
        assertEquals("1.21.4", profile.get("inheritsFrom").getAsString());
        assertEquals("Preferred Name", result.name());
        assertEquals("Preferred Name", profile.get("eclModpackName").getAsString());
        assertTrue(Files.isRegularFile(
                result.instanceDirectory().resolve(result.profileId() + ".mrpack")));
    }
}
