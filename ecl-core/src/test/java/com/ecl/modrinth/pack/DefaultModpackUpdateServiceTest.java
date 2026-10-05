package com.ecl.modrinth.pack;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ModrinthMetadataProvider;
import com.ecl.operation.InstanceOperationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultModpackUpdateServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void findsNewCompatibleVersionForSelectedPack() {
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
        ModpackInstance instance = new ModpackInstance(null, "example-pack", "Example Pack",
                "1.0", "1.21.1", "fabric", "pack-project", "pack-v1", tempDir.resolve("game/versions/example-pack"));
        ModpackUpdate update = service.checkUpdate(instance, ReleaseChannel.RELEASE_ONLY).join();

        assertEquals(instance, update.instance());
        assertEquals("pack-v2", update.availableVersion().id());
        assertEquals("pack.mrpack", update.selectedFile().fileName());
    }

    @Test
    void currentLatestVersionDoesNotProduceAnUpdate() {
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

        ModpackInstance instance = new ModpackInstance(null, "current-pack", "Current Pack",
                "2.0", "1.21.1", "fabric", "pack-project", "pack-v2", tempDir.resolve("game/versions/current-pack"));
        assertNull(service.checkUpdate(instance, ReleaseChannel.RELEASE_ONLY).join());
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
                new InstanceOperationCoordinator(), instanceId::equals);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.applyUpdate(new ModpackUpdate(instance, latest, packFile),
                        tempDir.resolve("game"), null).join());

        assertTrue(failure.getCause().getMessage().contains("running"));
    }
}
