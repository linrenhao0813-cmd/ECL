package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.service.InstanceUpdateService;
import com.ecl.modrinth.service.InstanceUpgradeRecoveryService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void preparesPackFileChangesWithoutChangingTheInstance() throws Exception {
        Path initial = tempDir.resolve("preview-initial.mrpack");
        writePack(initial, "Preview Pack", "1", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", "old=true", "mods/removed.jar", "old mod"));
        Path gameRoot = tempDir.resolve("preview-game");
        MrpackInstaller installer = new MrpackInstaller();
        MrpackInstaller.InstallResult installed = installer.install(
                initial.toFile(), gameRoot.toFile(), "Preview Pack", "project", "v1", null);
        Path updated = tempDir.resolve("preview-updated.mrpack");
        writePack(updated, "Preview Pack", "2", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", "old=false", "resourcepacks/new.zip", "new resource pack"));
        Path profileFile = ECLConfig.getVersionsDir().toPath().resolve(installed.profileId())
                .resolve(installed.profileId() + ".json");
        String beforeProfile = Files.readString(profileFile);
        String beforeManifest = Files.readString(installed.instanceDirectory()
                .resolve(PackManifest.FILE_NAME));
        Path config = installed.instanceDirectory().resolve("config/example.txt");
        Path oldMod = installed.instanceDirectory().resolve("mods/removed.jar");
        String beforeConfig = Files.readString(config);
        Path work = Files.createTempDirectory(ECLConfig.getBaseDir().toPath(), "ecl-modpack-preview-");

        try (MrpackInstaller.UpdatePreview preview = installer.prepareUpdate(updated.toFile(),
                gameRoot.toFile(), installed.profileId(), "project", "v2", work, null)) {
            assertTrue(preview.changes().stream().anyMatch(change ->
                    change.path().equals("config/example.txt")
                            && change.kind() == MrpackInstaller.FileChangeKind.REPLACED));
            assertTrue(preview.changes().stream().anyMatch(change ->
                    change.path().equals("mods/removed.jar")
                            && change.kind() == MrpackInstaller.FileChangeKind.DELETED));
            assertTrue(preview.changes().stream().anyMatch(change ->
                    change.path().equals("resourcepacks/new.zip")
                            && change.kind() == MrpackInstaller.FileChangeKind.ADDED));
            assertEquals(beforeProfile, Files.readString(profileFile));
            assertEquals(beforeManifest, Files.readString(installed.instanceDirectory()
                    .resolve(PackManifest.FILE_NAME)));
            assertEquals(beforeConfig, Files.readString(config));
            assertEquals("old mod", Files.readString(oldMod));
            assertFalse(Files.exists(installed.instanceDirectory().resolve("resourcepacks/new.zip")));

            installer.applyPreparedUpdate(preview, gameRoot.toFile(), () -> false, null);
            assertEquals("old=false", Files.readString(config));
            assertFalse(Files.exists(oldMod));
            assertEquals("new resource pack", Files.readString(
                    installed.instanceDirectory().resolve("resourcepacks/new.zip")));
        }
    }

    @Test
    void updatePreviewRejectsMinecraftOrLoaderTypeChanges() throws Exception {
        Path initial = tempDir.resolve("compat-initial.mrpack");
        writePack(initial, "Compat Pack", "1", "{\"minecraft\":\"1.21.4\"}", Map.of());
        Path gameRoot = tempDir.resolve("compat-game");
        MrpackInstaller installer = new MrpackInstaller();
        MrpackInstaller.InstallResult installed = installer.install(
                initial.toFile(), gameRoot.toFile(), "Compat Pack", "project", "v1", null);
        Path profile = ECLConfig.getVersionsDir().toPath().resolve(installed.profileId())
                .resolve(installed.profileId() + ".json");
        String beforeProfile = Files.readString(profile);
        String beforeManifest = Files.readString(installed.instanceDirectory()
                .resolve(PackManifest.FILE_NAME));
        Path changedMinecraft = tempDir.resolve("compat-minecraft.mrpack");
        writePack(changedMinecraft, "Compat Pack", "2", "{\"minecraft\":\"1.21.5\"}", Map.of());
        Path changedLoader = tempDir.resolve("compat-loader.mrpack");
        writePack(changedLoader, "Compat Pack", "3",
                "{\"minecraft\":\"1.21.4\",\"fabric-loader\":\"0.16.10\"}", Map.of());

        assertThrows(IOException.class, () -> installer.prepareUpdate(changedMinecraft.toFile(),
                gameRoot.toFile(), installed.profileId(), "project", "v2",
                Files.createTempDirectory(ECLConfig.getBaseDir().toPath(), "ecl-modpack-preview-"), null));
        assertThrows(IOException.class, () -> installer.prepareUpdate(changedLoader.toFile(),
                gameRoot.toFile(), installed.profileId(), "project", "v3",
                Files.createTempDirectory(ECLConfig.getBaseDir().toPath(), "ecl-modpack-preview-"), null));

        assertEquals(beforeProfile, Files.readString(profile));
        assertEquals(beforeManifest, Files.readString(installed.instanceDirectory()
                .resolve(PackManifest.FILE_NAME)));
    }

    @Test
    void rollbackRestoresUserFileFirstManagedByTheNewPack() throws Exception {
        Path initial = tempDir.resolve("rollback-initial.mrpack");
        writePack(initial, "Rollback Pack", "1", "{\"minecraft\":\"1.21.4\"}", Map.of());
        Path gameRoot = tempDir.resolve("rollback-game");
        MrpackInstaller installer = new MrpackInstaller();
        var installed = installer.install(initial.toFile(), gameRoot.toFile(), "Rollback Pack", "project", "v1", null);
        Path userFile = installed.instanceDirectory().resolve("config/user.txt");
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "user content");
        Path updated = tempDir.resolve("rollback-updated.mrpack");
        writePack(updated, "Rollback Pack", "2", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/user.txt", "pack content", "resourcepacks/new.zip", "new resourcepack"));
        Path versions = ECLConfig.getVersionsDir().toPath();
        var instance = VersionProfileModInstanceContext.load(installed.profileId(), versions, gameRoot);
        var pack = new ModpackInstance(instance.instanceId(), instance.profileId(), "Rollback Pack", "1",
                instance.minecraftVersion(), instance.loaderName(), "project", "v1", instance.gameDirectory());
        var version = TestFixtures.fabricVersion("v2", "project", List.of());
        var update = new ModpackUpdate(pack, version, version.files().getFirst());
        var preview = new InstanceUpdateService.Preview(instance, Instant.now(), versions, gameRoot,
                "fingerprint", null, List.of(), List.of(), Set.of(), List.of(), pack, update);
        var recovery = new InstanceUpgradeRecoveryService(tempDir.resolve("recovery"));
        Path work = Files.createTempDirectory(ECLConfig.getBaseDir().toPath(), "ecl-modpack-preview-");
        try (var prepared = installer.prepareUpdate(updated.toFile(), gameRoot.toFile(), instance.profileId(),
                "project", "v2", work, null)) {
            var execution = new InstanceUpdateService.PreparedExecution(preview, List.of(),
                    new ModpackUpdateService.PreparedUpdate(update, prepared));
            var draft = recovery.begin(execution);
            assertEquals("user content", Files.readString(userFile));
            installer.applyPreparedUpdate(prepared, gameRoot.toFile(), () -> false, null);
            assertEquals("pack content", Files.readString(userFile));
            recovery.complete(draft);
            assertTrue(recovery.restore(instance.profileId(), instance, versions, gameRoot, Set.of()).restored());
            assertEquals("user content", Files.readString(userFile));
            assertFalse(Files.exists(instance.gameDirectory().resolve("resourcepacks/new.zip")));
        }
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
        List<String> warnings = new ArrayList<>();
        installer.update(updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project", "v2", new MrpackInstaller.Listener() {
                    @Override public void onStatus(String message) { statuses.add(message); }
                    @Override public void onWarning(String message) {
                        warnings.add(message);
                        MrpackInstaller.Listener.super.onWarning(message);
                    }
                });

        assertEquals("user changed this", Files.readString(modified));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("mods/removed.jar")));
        assertTrue(statuses.stream().anyMatch(status -> status.contains("mods/removed.jar")));
    }

    @Test
    void updateReportsMissingManifestAsWarning() throws Exception {
        Path initial = tempDir.resolve("missing-manifest-initial.mrpack");
        writePack(initial, "Missing Manifest", "1", "{\"minecraft\":\"1.21.4\"}", Map.of("mods/old.jar", "old"));
        Path gameRoot = tempDir.resolve("game-missing-manifest");
        MrpackInstaller installer = new MrpackInstaller();
        var installed = installer.install(initial.toFile(), gameRoot.toFile(), "Missing Manifest", "project", "v1", null);
        Files.delete(installed.instanceDirectory().resolve(PackManifest.FILE_NAME));
        Path updated = tempDir.resolve("missing-manifest-updated.mrpack");
        writePack(updated, "Missing Manifest", "2", "{\"minecraft\":\"1.21.4\"}", Map.of());
        List<String> warnings = new ArrayList<>();
        installer.update(updated.toFile(), gameRoot.toFile(), installed.profileId(), "project", "v2", new MrpackInstaller.Listener() {
            @Override public void onStatus(String message) { }
            @Override public void onWarning(String message) { warnings.add(message); }
        });
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("未找到旧整合包文件清单")));
        assertTrue(Files.exists(installed.instanceDirectory().resolve("mods/old.jar")));
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
    void loaderFilesRollbackWhenPackTransactionCommitFails() throws Exception {
        Path initial = tempDir.resolve("loader-rollback-initial.mrpack");
        writePack(initial, "Loader Rollback Pack", "1", "{\"minecraft\":\"1.21.4\"}",
                Map.of("config/example.txt", "old=true"));
        Path gameRoot = tempDir.resolve("game-loader-rollback");
        MrpackInstaller.InstallResult installed = new MrpackInstaller().install(
                initial.toFile(), gameRoot.toFile(), "Loader Rollback Pack", "project", "v1", null);

        String loaderProfileId = "fabric-loader-0.16.10-1.21.5";
        MrpackInstaller updater = new MrpackInstaller(
                (versions, libraries, minecraft, loader, version, listener) -> {
                    Path profileDirectory = versions.resolve(loaderProfileId);
                    Files.createDirectories(profileDirectory);
                    Files.writeString(profileDirectory.resolve(loaderProfileId + ".json"),
                            "{\"id\":\"" + loaderProfileId + "\"}");
                    Path library = libraries.resolve("fabric/test-loader.jar");
                    Files.createDirectories(library.getParent());
                    Files.writeString(library, "loader");
                    return new com.ecl.launcher.ModLoaderInstaller.InstallResult(
                            loaderProfileId, minecraft, loader, version);
                },
                (instance, profile) -> new PackUpdateTransaction(instance, profile, 1));
        Path updated = tempDir.resolve("loader-rollback-updated.mrpack");
        writePack(updated, "Loader Rollback Pack", "2",
                "{\"minecraft\":\"1.21.5\",\"fabric-loader\":\"0.16.10\"}",
                Map.of("config/example.txt", "new=true"));

        assertThrows(IOException.class, () -> updater.update(
                updated.toFile(), gameRoot.toFile(), installed.profileId(),
                "project", "v2", null));

        Path loaderProfile = ECLConfig.getVersionsDir().toPath()
                .resolve(loaderProfileId).resolve(loaderProfileId + ".json");
        assertFalse(Files.exists(loaderProfile));
        assertFalse(Files.exists(ECLConfig.getLibrariesDir().toPath()
                .resolve("fabric/test-loader.jar")));
        assertEquals("old=true", Files.readString(
                installed.instanceDirectory().resolve("config/example.txt")));
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

    @Test
    void stalePreviewCleanupRemovesOnlyOldPreviewDirectories() throws Exception {
        Path base = ECLConfig.getBaseDir().toPath();
        Files.createDirectories(base);
        Path stale = Files.createDirectory(base.resolve(MrpackInstaller.PREVIEW_DIRECTORY_PREFIX + "stale"));
        Files.writeString(stale.resolve("leftover.bin"), "old");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis()));
        Path fresh = Files.createDirectory(base.resolve(MrpackInstaller.PREVIEW_DIRECTORY_PREFIX + "fresh"));
        Path versions = Files.createDirectory(base.resolve("versions"));
        Path notAPreview = Files.createDirectory(base.resolve("other-temp"));

        try {
            int removed = MrpackInstaller.cleanStalePreviews(java.time.Duration.ofHours(1));

            assertTrue(removed >= 1, "The abandoned preview directory must be removed");
            assertFalse(Files.exists(stale), "Stale preview directories are deleted");
            assertTrue(Files.exists(fresh), "Recent preview directories may still be in use");
            assertTrue(Files.exists(notAPreview), "Only preview directories are cleaned");
            assertTrue(Files.exists(versions), "Managed data roots are never touched");
        } finally {
            Files.deleteIfExists(fresh);
            Files.deleteIfExists(notAPreview);
            Files.deleteIfExists(versions);
        }
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
