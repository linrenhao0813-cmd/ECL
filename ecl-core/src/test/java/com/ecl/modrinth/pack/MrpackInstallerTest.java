package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void persistsSourceIdentityAndUpdatesPackInPlaceWithoutRemovingUserFiles() throws Exception {
        Path initial = tempDir.resolve("initial.mrpack");
        writePack(initial, "Example Pack", "1", "old=true");
        Path gameRoot = tempDir.resolve("game-update");
        MrpackInstaller installer = new MrpackInstaller();
        MrpackInstaller.InstallResult installed = installer.install(
                initial.toFile(), gameRoot.toFile(), "Example Pack", "project-id", "version-1", null);
        Files.createDirectories(installed.instanceDirectory().resolve("saves"));
        Files.writeString(installed.instanceDirectory().resolve("saves/world.txt"), "keep me");

        Path updated = tempDir.resolve("updated.mrpack");
        writePack(updated, "Example Pack", "2", "old=false");
        installer.update(updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project-id", "version-2", null);

        assertEquals("old=false",
                Files.readString(installed.instanceDirectory().resolve("config/example.txt")));
        assertEquals("keep me",
                Files.readString(installed.instanceDirectory().resolve("saves/world.txt")));
        Path profileJson = ECLConfig.getVersionsDir().toPath()
                .resolve(installed.profileId()).resolve(installed.profileId() + ".json");
        JsonObject profile = JsonParser.parseString(Files.readString(profileJson)).getAsJsonObject();
        assertEquals("project-id", profile.get("eclModpackProjectId").getAsString());
        assertEquals("version-2", profile.get("eclModpackVersionId").getAsString());
        assertEquals("2", profile.get("eclModpackVersion").getAsString());
    }

    @Test
    void updateDeletesUnmodifiedFileRemovedByNewPack() throws Exception {
        Path initial = tempDir.resolve("delete-initial.mrpack");
        writePack(initial, "Delete Pack", "1", "{\"minecraft\":\"1.21.4\"}",
                Map.of("mods/removed.jar", "old mod", "config/example.txt", "old=true"));
        Path gameRoot = tempDir.resolve("game-delete");
        MrpackInstaller installer = new MrpackInstaller();
        MrpackInstaller.InstallResult installed = installer.install(
                initial.toFile(), gameRoot.toFile(), "Delete Pack", "project", "v1", null);

        Path updated = tempDir.resolve("delete-updated.mrpack");
        writePack(updated, "Delete Pack", "2", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", "old=false"));
        installer.update(updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project", "v2", null);

        assertFalse(Files.exists(installed.instanceDirectory().resolve("mods/removed.jar")));
        assertEquals("old=false",
                Files.readString(installed.instanceDirectory().resolve("config/example.txt")));
    }

    @Test
    void updatePreservesUserModifiedFileRemovedByNewPackAndWarns() throws Exception {
        Path initial = tempDir.resolve("modified-initial.mrpack");
        writePack(initial, "Modified Pack", "1", "{\"minecraft\":\"1.21.4\"}",
                Map.of("mods/removed.jar", "old mod"));
        Path gameRoot = tempDir.resolve("game-modified");
        MrpackInstaller installer = new MrpackInstaller();
        MrpackInstaller.InstallResult installed = installer.install(
                initial.toFile(), gameRoot.toFile(), "Modified Pack", "project", "v1", null);
        Path modified = installed.instanceDirectory().resolve("mods/removed.jar");
        Files.writeString(modified, "user changed this");

        Path updated = tempDir.resolve("modified-updated.mrpack");
        writePack(updated, "Modified Pack", "2", "{\"minecraft\":\"1.21.4\"}", Map.of());
        List<String> statuses = new ArrayList<>();
        installer.update(updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project", "v2", statuses::add);

        assertEquals("user changed this", Files.readString(modified));
        assertTrue(statuses.stream().anyMatch(status -> status.contains("mods/removed.jar")));
    }

    @Test
    void updateFailureRollsBackFilesArchiveManifestAndProfile() throws Exception {
        Path initial = tempDir.resolve("rollback-initial.mrpack");
        writePack(initial, "Rollback Pack", "1", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", "old=true", "mods/removed.jar", "old mod"));
        Path gameRoot = tempDir.resolve("game-rollback");
        MrpackInstaller.InstallResult installed = new MrpackInstaller().install(
                initial.toFile(), gameRoot.toFile(), "Rollback Pack", "project", "v1", null);
        Path profileFile = ECLConfig.getVersionsDir().toPath().resolve(installed.profileId())
                .resolve(installed.profileId() + ".json");
        String oldProfile = Files.readString(profileFile);
        String oldManifest = Files.readString(
                installed.instanceDirectory().resolve(PackManifest.FILE_NAME));
        byte[] oldArchive = Files.readAllBytes(
                installed.instanceDirectory().resolve(installed.profileId() + ".mrpack"));

        Path updated = tempDir.resolve("rollback-updated.mrpack");
        writePack(updated, "Rollback Pack", "2", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", "old=false"));
        MrpackInstaller failing = new MrpackInstaller(
                (minecraft, loader, version, listener) -> {
                    throw new AssertionError("No loader install expected");
                },
                (instance, profile) -> new PackUpdateTransaction(instance, profile, 1));

        assertThrows(IOException.class, () -> failing.update(
                updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project", "v2", null));
        assertEquals("old=true", Files.readString(
                installed.instanceDirectory().resolve("config/example.txt")));
        assertEquals("old mod", Files.readString(
                installed.instanceDirectory().resolve("mods/removed.jar")));
        assertEquals(oldProfile, Files.readString(profileFile));
        assertEquals(oldManifest, Files.readString(
                installed.instanceDirectory().resolve(PackManifest.FILE_NAME)));
        assertTrue(java.util.Arrays.equals(oldArchive, Files.readAllBytes(
                installed.instanceDirectory().resolve(installed.profileId() + ".mrpack"))));
    }

    @Test
    void loaderVersionChangeInstallsLoaderAndUpdatesProfileMetadata() throws Exception {
        Path initial = tempDir.resolve("loader-initial.mrpack");
        writePack(initial, "Loader Pack", "1", "{\"minecraft\":\"1.21.4\"}", Map.of());
        Path gameRoot = tempDir.resolve("game-loader");
        MrpackInstaller.InstallResult installed = new MrpackInstaller().install(
                initial.toFile(), gameRoot.toFile(), "Loader Pack", "project", "v1", null);

        AtomicBoolean loaderInstalled = new AtomicBoolean();
        MrpackInstaller updater = new MrpackInstaller(
                (minecraft, loader, version, listener) -> {
                    loaderInstalled.set(true);
                    assertEquals("1.21.5", minecraft);
                    assertEquals(com.ecl.launcher.ModLoaderInstaller.Loader.FABRIC, loader);
                    assertEquals("0.16.10", version);
                    return new com.ecl.launcher.ModLoaderInstaller.InstallResult(
                            "fabric-loader-0.16.10-1.21.5", minecraft, loader, version);
                }, PackUpdateTransaction::new);
        Path updated = tempDir.resolve("loader-updated.mrpack");
        writePack(updated, "Loader Pack", "2",
                "{\"minecraft\":\"1.21.5\",\"fabric-loader\":\"0.16.10\"}", Map.of());

        updater.update(updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project", "v2", null);

        assertTrue(loaderInstalled.get());
        Path profileFile = ECLConfig.getVersionsDir().toPath().resolve(installed.profileId())
                .resolve(installed.profileId() + ".json");
        JsonObject profile = JsonParser.parseString(Files.readString(profileFile)).getAsJsonObject();
        assertEquals("fabric-loader-0.16.10-1.21.5",
                profile.get("inheritsFrom").getAsString());
        assertEquals("fabric", profile.get("eclModLoader").getAsString());
        assertEquals("0.16.10", profile.get("eclModLoaderVersion").getAsString());
        assertEquals("1.21.5", profile.get("eclMinecraftVersion").getAsString());
    }

    @Test
    void installContentsKeepsChineseMessageWhenDependenciesMissing() throws Exception {
        Path archive = tempDir.resolve("no-deps.mrpack");
        String index = """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "name": "No Dependencies",
                  "versionId": "1",
                  "files": []
                }
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(index.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Path instance = tempDir.resolve("staging");
        IOException error = assertThrows(IOException.class,
                () -> new MrpackInstaller().installContents(archive.toFile(), instance, null));

        assertTrue(error.getMessage().contains("整合包索引缺少 dependencies"));
    }

    @Test
    void findLoaderPrefersMultiLoaderConflictOverInvalidValue() throws Exception {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("fabric-loader", "0.16.10");
        dependencies.addProperty("forge", 12345);

        IOException error = assertThrows(IOException.class,
                () -> MrpackDependencyResolver.findLoader(dependencies));

        assertTrue(error.getMessage().contains("整合包同时声明了多个模组加载器"));
    }

    private static void writePack(Path archive, String name, String version, String config)
            throws Exception {
        writePack(archive, name, version, "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", config));
    }

    private static void writePack(Path archive, String name, String version,
                                  String dependencies, Map<String, String> overrides)
            throws Exception {
        String index = """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "name": "%s",
                  "versionId": "%s",
                  "files": [],
                  "dependencies": %s
                }
                """.formatted(name, version, dependencies);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(index.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, String> override : overrides.entrySet()) {
                zip.putNextEntry(new ZipEntry("overrides/" + override.getKey()));
                zip.write(override.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
