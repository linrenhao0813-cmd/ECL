package com.ecl.launcher;

import com.ecl.util.FileLockLease;
import com.ecl.util.ManagedLockPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            Files.writeString(parent.resolve(com.ecl.ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER),
                    "complete");
            Files.writeString(child.resolve("child.json"), "{\"id\":\"child\",\"inheritsFrom\":\"base\"}");

            assertEquals(true, new VersionManager().isVersionDownloaded("child"));
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void loaderProfilesRemainDistinctAndDisplayTheirLoader(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path fabric = tempDir.resolve("versions/1.21.1-fabric-loader-0.16.9");
            Path forge = tempDir.resolve("versions/1.21.1-forge-52.0.1");
            Files.createDirectories(fabric);
            Files.createDirectories(forge);
            Files.writeString(fabric.resolve("1.21.1-fabric-loader-0.16.9.json"),
                    "{\"id\":\"1.21.1-fabric-loader-0.16.9\",\"inheritsFrom\":\"1.21.1\","
                            + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\"}");
            Files.writeString(forge.resolve("1.21.1-forge-52.0.1.json"),
                    "{\"id\":\"1.21.1-forge-52.0.1\",\"inheritsFrom\":\"1.21.1\","
                            + "\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\","
                            + "\"libraries\":[{\"name\":\"net.minecraftforge:forge:1.21.1-52.0.1\"}]}");

            VersionManager manager = new VersionManager();
            List<String> merged = manager.mergeLocalLoaderProfiles(List.of("1.21.1"));
            assertEquals(List.of("1.21.1", "1.21.1-fabric-loader-0.16.9", "1.21.1-forge-52.0.1"), merged);
            assertEquals("1.21.1 · Fabric  [1.21.1-fabric-loader-0.16.9]",
                    manager.getVersionDisplayName("1.21.1-fabric-loader-0.16.9"));
            assertEquals("1.21.1 · Forge  [1.21.1-forge-52.0.1]",
                    manager.getVersionDisplayName("1.21.1-forge-52.0.1"));
            assertEquals("1.21.1 · 原版", manager.getVersionDisplayName("1.21.1"));
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void migratesCompleteLegacyInstallationToCompletionMarker(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            byte[] client = "legacy-client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha1 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(client));
            Path version = tempDir.resolve("versions/legacy");
            Files.createDirectories(version);
            Files.write(version.resolve("legacy.jar"), client);
            Files.writeString(version.resolve("legacy.json"), """
                    {"id":"legacy","downloads":{"client":{
                      "sha1":"%s","size":%d
                    }},"libraries":[]}
                    """.formatted(sha1, client.length));

            VersionManager manager = new VersionManager();
            assertFalse(manager.isVersionDownloaded("legacy"));
            assertTrue(manager.ensureVersionDownloadedAsync("legacy").get());
            assertTrue(manager.isVersionDownloaded("legacy"));
            assertEquals("legacy-verified", Files.readString(version.resolve(
                    com.ecl.ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER)));
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void doesNotMigrateLegacyInstallationWithMissingLibrary(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            byte[] client = "partial-client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha1 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(client));
            Path version = tempDir.resolve("versions/partial");
            Files.createDirectories(version);
            Files.write(version.resolve("partial.jar"), client);
            Files.writeString(version.resolve("partial.json"), """
                    {"id":"partial","downloads":{"client":{
                      "sha1":"%s","size":%d
                    }},"libraries":[{"downloads":{"artifact":{
                      "path":"missing/library.jar",
                      "sha1":"%s","size":1
                    }}}]}
                    """.formatted(sha1, client.length, "0".repeat(40)));

            VersionManager manager = new VersionManager();
            assertFalse(manager.ensureVersionDownloadedAsync("partial").get());
            assertFalse(manager.isVersionDownloaded("partial"));
            assertFalse(Files.exists(version.resolve(
                    com.ecl.ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER)));
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void legacyMigrationDoesNotRaceAnActiveVersionDownload(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            byte[] client = "locked-client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha1 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(client));
            Path version = tempDir.resolve("versions/locked");
            Files.createDirectories(version);
            Files.write(version.resolve("locked.jar"), client);
            Files.writeString(version.resolve("locked.json"), """
                    {"id":"locked","downloads":{"client":{
                      "sha1":"%s","size":%d
                    }},"libraries":[]}
                    """.formatted(sha1, client.length));
            Path versionLock = ManagedLockPaths.versionDownload(
                    com.ecl.ECLConfig.getVersionsDir().toPath(), "locked");
            VersionManager manager = new VersionManager();

            try (FileLockLease ignored = FileLockLease.tryAcquire(versionLock)) {
                assertTrue(ignored != null);
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> manager.ensureVersionDownloadedAsync("locked").get());
            }

            assertFalse(Files.exists(version.resolve(
                    com.ecl.ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER)));
            assertTrue(manager.ensureVersionDownloadedAsync("locked").get());
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void loaderProfileResolvesMissingParentAsDownloadTarget(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path child = tempDir.resolve("versions/fabric-loader-0.16.14-1.21.4");
            Files.createDirectories(child);
            Files.writeString(child.resolve("fabric-loader-0.16.14-1.21.4.json"), """
                    {
                      "id":"fabric-loader-0.16.14-1.21.4",
                      "inheritsFrom":"1.21.4",
                      "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient"
                    }
                    """);

            VersionManager manager = new VersionManager();
            JsonObject manifestVersion = JsonParser.parseString("""
                    {"id":"1.21.4","type":"release","url":"https://example.invalid/1.21.4.json"}
                    """).getAsJsonObject();
            setManifest(manager, manifestVersion);

            VersionManager.VersionDownloadTarget target =
                    manager.resolveDownloadTarget("fabric-loader-0.16.14-1.21.4");

            assertEquals("1.21.4", target.downloadVersionId());
            assertEquals("https://example.invalid/1.21.4.json", target.versionUrl());
            assertFalse(manager.isVersionDownloaded("fabric-loader-0.16.14-1.21.4"));

            Path parent = tempDir.resolve("versions/1.21.4");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve("1.21.4.json"), "{\"id\":\"1.21.4\"}");
            Files.write(parent.resolve("1.21.4.jar"), new byte[]{1});
            Files.writeString(parent.resolve(com.ecl.ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER),
                    "complete");
            assertTrue(manager.isVersionDownloaded("fabric-loader-0.16.14-1.21.4"));
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void unmatchedLocalProfileIsInsertedNearItsMinecraftVersion(@TempDir Path tempDir) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path fabric = tempDir.resolve("versions/fabric-1.21.4");
            Files.createDirectories(fabric);
            Files.writeString(fabric.resolve("fabric-1.21.4.json"), """
                    {
                      "id":"fabric-1.21.4",
                      "inheritsFrom":"1.21.4",
                      "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient"
                    }
                    """);

            List<String> merged = new VersionManager().mergeLocalLoaderProfiles(
                    List.of("1.21.5", "1.21.3"));

            assertEquals(List.of("1.21.5", "fabric-1.21.4", "1.21.3"), merged);
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void minecraftVersionResolutionRejectsUnknownRootButAcceptsMissingInheritedMetadata(
            @TempDir Path tempDir
    ) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path child = tempDir.resolve("versions/forge-1.21.4");
            Files.createDirectories(child);
            Files.writeString(child.resolve("forge-1.21.4.json"), """
                    {"id":"forge-1.21.4","inheritsFrom":"1.21.4"}
                    """);

            VersionManager manager = new VersionManager();
            assertEquals("1.21.4", manager.resolveMinecraftVersionId("forge-1.21.4"));
            assertThrows(java.io.IOException.class,
                    () -> manager.resolveMinecraftVersionId("not-a-real-profile"));
        } finally {
            baseDir.set(null, previous);
        }
    }

    private static void setManifest(VersionManager manager, JsonObject version) throws Exception {
        JsonObject manifest = new JsonObject();
        com.google.gson.JsonArray versions = new com.google.gson.JsonArray();
        versions.add(version);
        manifest.add("versions", versions);

        Field manifestField = VersionManager.class.getDeclaredField("manifest");
        manifestField.setAccessible(true);
        manifestField.set(manager, manifest);
        Field indexField = VersionManager.class.getDeclaredField("versionIndex");
        indexField.setAccessible(true);
        indexField.set(manager, Map.of(version.get("id").getAsString(), version));
    }
}
