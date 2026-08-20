package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ModrinthMetadataProvider;
import com.ecl.modrinth.service.DefaultInstanceOperationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultModpackUpdateServiceTest {
    @TempDir
    Path tempDir;

    private Field baseDirField;
    private java.io.File previousBaseDir;

    @BeforeEach
    void useTemporaryBaseDirectory() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (java.io.File) baseDirField.get(null);
        baseDirField.set(null, tempDir.resolve("ecl").toFile());
        ECLConfig.ensureDirs();
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void findsNewCompatibleVersionFromPersistedModrinthIdentity() throws Exception {
        Path profileDirectory = Files.createDirectories(
                ECLConfig.getVersionsDir().toPath().resolve("example-pack"));
        Files.writeString(profileDirectory.resolve("example-pack.json"), """
                {
                  "id": "example-pack",
                  "eclMinecraftVersion": "1.21.1",
                  "eclModLoader": "fabric",
                  "eclModpackName": "Example Pack",
                  "eclModpackVersion": "1.0",
                  "eclModpackSource": "modrinth",
                  "eclModpackProjectId": "pack-project",
                  "eclModpackVersionId": "pack-v1"
                }
                """);

        ModFile packFile = new ModFile(URI.create("https://example.invalid/pack.mrpack"),
                "pack.mrpack", Map.of(), true, 42, "");
        ModVersion current = TestFixtures.version("pack-v1", "pack-project", "release", false,
                List.of("1.21.1"), List.of("fabric"), Instant.parse("2026-01-01T00:00:00Z"),
                List.of(packFile), List.of());
        ModVersion latest = TestFixtures.version("pack-v2", "pack-project", "release", false,
                List.of("1.21.1"), List.of("fabric"), Instant.parse("2026-02-01T00:00:00Z"),
                List.of(packFile), List.of());
        TestFixtures.FakeApi api = new TestFixtures.FakeApi();
        api.projectVersions.put("pack-project", List.of(current, latest));

        DefaultModpackUpdateService service = new DefaultModpackUpdateService(
                new ModrinthMetadataProvider(api, false), Runnable::run);
        List<ModpackUpdate> updates = service.checkUpdates(
                tempDir.resolve("game"), ReleaseChannel.RELEASE_ONLY).join();

        assertEquals(1, updates.size());
        assertEquals("example-pack", updates.getFirst().instance().profileId());
        assertEquals("pack-v2", updates.getFirst().availableVersion().id());
        assertEquals("pack.mrpack", updates.getFirst().selectedFile().fileName());
    }

    @Test
    void currentLatestVersionDoesNotProduceAnUpdate() throws Exception {
        Path profileDirectory = Files.createDirectories(
                ECLConfig.getVersionsDir().toPath().resolve("current-pack"));
        Files.writeString(profileDirectory.resolve("current-pack.json"), """
                {
                  "id": "current-pack",
                  "eclMinecraftVersion": "1.21.1",
                  "eclModLoader": "fabric",
                  "eclModpackSource": "modrinth",
                  "eclModpackProjectId": "pack-project",
                  "eclModpackVersionId": "pack-v2"
                }
                """);
        ModFile packFile = new ModFile(URI.create("https://example.invalid/pack.mrpack"),
                "pack.mrpack", Map.of(), true, 42, "");
        ModVersion older = TestFixtures.version("pack-v1", "pack-project", "release", false,
                List.of("1.21.1"), List.of("fabric"), Instant.parse("2026-01-01T00:00:00Z"),
                List.of(packFile), List.of());
        ModVersion current = TestFixtures.version("pack-v2", "pack-project", "release", false,
                List.of("1.21.1"), List.of("fabric"), Instant.parse("2026-02-01T00:00:00Z"),
                List.of(packFile), List.of());
        TestFixtures.FakeApi api = new TestFixtures.FakeApi();
        api.projectVersions.put("pack-project", List.of(older, current));

        DefaultModpackUpdateService service = new DefaultModpackUpdateService(
                new ModrinthMetadataProvider(api, false), Runnable::run);

        assertTrue(service.checkUpdates(tempDir.resolve("game"), ReleaseChannel.RELEASE_ONLY)
                .join().isEmpty());
    }

    @Test
    void runningInstanceCannotApplyUpdate() {
        Path instanceDirectory = tempDir.resolve("game/versions/running-pack");
        UUID instanceId = ModpackInstance.instanceIdFor(instanceDirectory);
        ModpackInstance instance = new ModpackInstance(instanceId, "running-pack", "Running Pack",
                "1", "1.21.1", "fabric", "pack-project", "pack-v1", instanceDirectory);
        ModFile packFile = new ModFile(URI.create("https://example.invalid/pack.mrpack"),
                "pack.mrpack", Map.of(), true, 42, "");
        ModVersion latest = TestFixtures.version("pack-v2", "pack-project", "release", false,
                List.of("1.21.1"), List.of("fabric"), Instant.parse("2026-02-01T00:00:00Z"),
                List.of(packFile), List.of());
        TestFixtures.FakeApi api = new TestFixtures.FakeApi();
        DefaultModpackUpdateService service = new DefaultModpackUpdateService(
                new ModrinthMetadataProvider(api, false), Runnable::run,
                new DefaultInstanceOperationLock(), instanceId::equals);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.applyUpdate(new ModpackUpdate(instance, latest, packFile),
                        tempDir.resolve("game"), null).join());

        assertTrue(failure.getCause().getMessage().contains("running"));
    }
}
