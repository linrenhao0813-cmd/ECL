package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.pack.ModpackInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceUpgradeRecoveryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void ordinaryModRecoveryRejectsNormalizedUserDataAlias() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/mods-only");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("mods-only/mods-only.json");
        Files.createDirectories(instanceRoot.resolve("logs"));
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "metadata");
        Path index = Files.writeString(instanceRoot.resolve("launcher-mods.json"), "old index");
        Path log = Files.writeString(instanceRoot.resolve("logs/latest.log"), "user log");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "mods-only", instanceRoot);
        Path recoveryRoot = tempDir.resolve("recovery");
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(recoveryRoot);
        var preview = new InstanceUpdateService.Preview(instance, Instant.now(), metadataRoot, game,
                "fingerprint", null, List.of(), List.of(), Set.of(), List.of(), null, null);
        var draft = service.begin(new InstanceUpdateService.PreparedExecution(preview, List.of()));
        Files.writeString(index, "new index");
        service.complete(draft);
        Path file = recoveryRoot.resolve("mods-only/active/recovery.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        manifest.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("path", "mods/../logs/latest.log");
        Files.writeString(file, manifest.toString());
        assertThrows(IOException.class, () -> service.inspect("mods-only", instance, metadataRoot, game));
        assertThrows(IOException.class, () -> service.restore("mods-only", instance, metadataRoot, game, Set.of()));
        assertEquals("user log", Files.readString(log));
        assertEquals("new index", Files.readString(index));
    }

    @Test
    void publicationCrashPreservesPreviousAndResumesPending() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/crash");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("crash/crash.json");
        Files.createDirectories(instanceRoot);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "first");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "crash", instanceRoot);
        Path recoveryRoot = tempDir.resolve("recovery");
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(recoveryRoot);
        var execution = new InstanceUpdateService.PreparedExecution(preview(instance, metadataRoot, game), List.of());
        var first = service.begin(execution);
        Files.writeString(metadata, "second");
        service.complete(first);
        InstanceUpgradeRecoveryService crashing = new InstanceUpgradeRecoveryService(recoveryRoot, null, ignored -> false,
                phase -> { if (phase.equals("after-active-moved")) throw new AssertionError("power loss"); });
        var second = crashing.begin(execution);
        Files.writeString(metadata, "third");
        assertThrows(AssertionError.class, () -> crashing.complete(second));
        assertTrue(Files.exists(recoveryRoot.resolve("crash/previous/recovery.json")));
        assertTrue(Files.exists(recoveryRoot.resolve("crash/pending/recovery.json")));
        assertTrue(service.restore("crash", instance, metadataRoot, game, Set.of()).restored());
        assertEquals("second", Files.readString(metadata));
    }

    @Test
    void snapshotFailurePreservesPreviousPoint() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/snapshot");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("snapshot/snapshot.json");
        Files.createDirectories(instanceRoot);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "first");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "snapshot", instanceRoot);
        Path recoveryRoot = tempDir.resolve("recovery");
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(recoveryRoot);
        var execution = new InstanceUpdateService.PreparedExecution(preview(instance, metadataRoot, game), List.of());
        var first = service.begin(execution);
        Files.writeString(metadata, "second");
        service.complete(first);
        Path active = recoveryRoot.resolve("snapshot/active/recovery.json");
        String before = Files.readString(active);
        InstanceUpgradeRecoveryService failing = new InstanceUpgradeRecoveryService(recoveryRoot, null, ignored -> false,
                phase -> { if (phase.equals("before-payload-copy")) throw new IOException("disk full"); });
        assertThrows(IOException.class, () -> failing.begin(execution));
        assertEquals(before, Files.readString(active));
        assertEquals("second", Files.readString(metadata));
        assertTrue(service.restore("snapshot", instance, metadataRoot, game, Set.of()).restored());
        assertEquals("first", Files.readString(metadata));
    }

    @Test
    void recoversInterruptedActivePreviousSwapAndResumesRestoring() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/swap");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("swap/swap.json");
        Files.createDirectories(instanceRoot);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "before");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "swap", instanceRoot);
        Path recoveryRoot = tempDir.resolve("recovery");
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(recoveryRoot);
        var execution = new InstanceUpdateService.PreparedExecution(preview(instance, metadataRoot, game), List.of());
        var draft = service.begin(execution);
        Files.writeString(metadata, "after");
        service.complete(draft);
        Path active = recoveryRoot.resolve("swap/active");
        JsonObject manifest = JsonParser.parseString(Files.readString(active.resolve("recovery.json"))).getAsJsonObject();
        manifest.addProperty("state", "RESTORING");
        manifest.addProperty("conflictsConfirmed", true);
        Files.writeString(active.resolve("recovery.json"), manifest.toString());
        Files.move(active, active.resolveSibling("previous"));
        assertTrue(service.restore("swap", instance, metadataRoot, game, Set.of()).restored());
        assertEquals("before", Files.readString(metadata));
        assertFalse(Files.exists(active.resolveSibling("previous")));
    }

    @Test
    void invalidPendingPreservesActiveAndPending() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/invalid-pending");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("invalid-pending/invalid-pending.json");
        Files.createDirectories(instanceRoot);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "first");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "invalid-pending", instanceRoot);
        Path recoveryRoot = tempDir.resolve("recovery");
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(recoveryRoot);
        var execution = new InstanceUpdateService.PreparedExecution(preview(instance, metadataRoot, game), List.of());
        var first = service.begin(execution);
        Files.writeString(metadata, "second");
        service.complete(first);
        Path active = recoveryRoot.resolve("invalid-pending/active/recovery.json");
        String before = Files.readString(active);
        var pending = service.begin(execution);
        Path pendingManifest = pending.directory().resolve("recovery.json");
        Files.writeString(pendingManifest, "broken");
        Files.writeString(metadata, "third");
        pending.close();
        assertThrows(IOException.class, () -> service.restore("invalid-pending", instance, metadataRoot, game, Set.of()));
        assertEquals(before, Files.readString(active));
        assertEquals("broken", Files.readString(pendingManifest));
        assertEquals("third", Files.readString(metadata));
    }

    @Test
    void directRestoreFinalizesPendingBeforeChoosingActive() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/pending");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("pending/pending.json");
        Files.createDirectories(instanceRoot);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "before");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "pending", instanceRoot);
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(tempDir.resolve("recovery"));
        var execution = new InstanceUpdateService.PreparedExecution(preview(instance, metadataRoot, game), List.of());
        var draft = service.begin(execution);
        Files.writeString(metadata, "after");
        draft.close();
        assertTrue(service.restore("pending", instance, metadataRoot, game, Set.of()).restored());
        assertEquals("before", Files.readString(metadata));
        assertEquals("RESTORED", service.inspect("pending", instance, metadataRoot, game).state());
    }

    @Test
    void restoresPackManagedChangesAndMetadataButLeavesSavesAlone() throws Exception {
        Path recoveryRoot = tempDir.resolve("ecl/upgrade-recovery");
        Path metadataRoot = tempDir.resolve("ecl/versions");
        Path gameRoot = tempDir.resolve("minecraft");
        Path instanceRoot = gameRoot.resolve("versions/pack-test");
        Path profileFile = metadataRoot.resolve("pack-test/pack-test.json");
        Path mod = instanceRoot.resolve("mods/example.jar");
        Path config = instanceRoot.resolve("config/pack.cfg");
        Path save = instanceRoot.resolve("saves/world/level.dat");
        Path newResourcepack = instanceRoot.resolve("resourcepacks/new.zip");
        Files.createDirectories(mod.getParent());
        Files.createDirectories(config.getParent());
        Files.createDirectories(save.getParent());
        Files.createDirectories(profileFile.getParent());
        Files.createDirectories(instanceRoot.resolve(".ecl/config"));
        Files.writeString(mod, "old mod");
        Files.writeString(config, "old config");
        Files.writeString(save, "old world");
        Files.writeString(profileFile, "old version profile");
        Path launchProfile = instanceRoot.resolve(".ecl/config/launch-profile.json");
        Files.writeString(launchProfile, "old launch profile");
        Files.writeString(instanceRoot.resolve("launcher-mods.json"), "old mod index");
        Files.writeString(instanceRoot.resolve(".ecl-pack-manifest.json"), manifest("1",
                Map.of("mods/example.jar", mod, "config/pack.cfg", config)));

        TestInstance instance = new TestInstance(UUID.randomUUID(), "pack-test", instanceRoot);
        InstanceUpdateService.Preview preview = preview(instance, metadataRoot, gameRoot);
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(recoveryRoot);
        InstanceUpgradeRecoveryService.Draft draft = service.begin(
                new InstanceUpdateService.PreparedExecution(preview, List.of()));

        Files.writeString(mod, "new mod");
        Files.delete(config);
        Files.createDirectories(newResourcepack.getParent());
        Files.writeString(newResourcepack, "new resourcepack");
        Files.writeString(profileFile, "new version profile");
        Files.writeString(launchProfile, "new launch profile");
        Files.writeString(instanceRoot.resolve("launcher-mods.json"), "new mod index");
        Files.writeString(instanceRoot.resolve(".ecl-pack-manifest.json"), manifest("2",
                Map.of("mods/example.jar", mod, "resourcepacks/new.zip", newResourcepack)));
        Files.writeString(save, "user world after upgrade");
        assertTrue(service.hasUnfinishedOperation("pack-test"));

        InstanceUpgradeRecoveryService competing = new InstanceUpgradeRecoveryService(recoveryRoot);
        assertThrows(IOException.class, () -> competing.inspect("pack-test", instance, metadataRoot, gameRoot));
        assertThrows(IOException.class, () -> competing.restore("pack-test", instance, metadataRoot, gameRoot, Set.of()));
        assertTrue(Files.isDirectory(draft.directory()));
        draft.close(); // Simulate the OS releasing the lease after the updating process exits.

        Path recoveryManifest = recoveryRoot.resolve("pack-test/active/recovery.json");
        InstanceUpgradeRecoveryService.RestorePoint point = service.inspect(
                "pack-test", instance, metadataRoot, gameRoot);
        assertFalse(service.hasUnfinishedOperation("pack-test"));
        String validManifest = Files.readString(recoveryManifest);
        Path payload;
        try (var files = Files.list(recoveryRoot.resolve("pack-test/active/payload"))) {
            payload = files.findFirst().orElseThrow();
        }
        byte[] validPayload = Files.readAllBytes(payload);
        Files.writeString(payload, "corrupted backup");
        assertThrows(IOException.class,
                () -> service.inspect("pack-test", instance, metadataRoot, gameRoot));
        Files.write(payload, validPayload);

        JsonObject maliciousManifest = JsonParser.parseString(validManifest).getAsJsonObject();
        JsonArray entries = maliciousManifest.getAsJsonArray("entries");
        entries.get(0).getAsJsonObject().addProperty("path", "../../outside.txt");
        Files.writeString(recoveryManifest, maliciousManifest.toString());
        assertThrows(IOException.class, () -> service.inspect(
                "pack-test", instance, metadataRoot, gameRoot));
        Files.writeString(recoveryManifest, validManifest);

        point = service.inspect("pack-test", instance, metadataRoot, gameRoot);
        assertTrue(point.files().stream().anyMatch(path -> path.endsWith("mods/example.jar")));
        assertFalse(point.files().stream().anyMatch(path -> path.contains("saves/")));

        Files.writeString(mod, "user edit after upgrade");
        point = service.inspect("pack-test", instance, metadataRoot, gameRoot);
        assertTrue(point.conflicts().stream().anyMatch(path -> path.endsWith("mods/example.jar")));
        InstanceUpgradeRecoveryService.RestoreResult denied = service.restore(
                "pack-test", instance, metadataRoot, gameRoot, Set.of());
        assertFalse(denied.restored());

        InstanceUpgradeRecoveryService.RestoreResult restored = service.restore(
                "pack-test", instance, metadataRoot, gameRoot, Set.copyOf(denied.conflicts()));
        assertTrue(restored.restored());
        assertEquals("old mod", Files.readString(mod));
        assertEquals("old config", Files.readString(config));
        assertFalse(Files.exists(newResourcepack));
        assertEquals("old version profile", Files.readString(profileFile));
        assertEquals("new launch profile", Files.readString(launchProfile));
        assertEquals("old mod index", Files.readString(instanceRoot.resolve("launcher-mods.json")));
        assertEquals("user world after upgrade", Files.readString(save));
        assertFalse(service.hasUnfinishedOperation("pack-test"));
    }

    @Test
    void failedRestorePointCreationLeavesTheInstanceUntouched() throws Exception {
        Path versions = tempDir.resolve("metadata");
        Path gameRoot = tempDir.resolve("game");
        Path instanceRoot = gameRoot.resolve("versions/no-update");
        Path profile = versions.resolve("no-update/no-update.json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, "before metadata");
        Files.createDirectories(instanceRoot.resolve("mods"));
        Path mod = instanceRoot.resolve("mods/a.jar");
        Files.writeString(mod, "before mod");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "no-update", instanceRoot);
        Path blockedRoot = tempDir.resolve("recovery-root-is-a-file");
        Files.writeString(blockedRoot, "cannot create recovery directories");
        InstanceUpdateService.Preview preview = new InstanceUpdateService.Preview(
                instance, Instant.now(), versions, gameRoot, "fingerprint", null,
                List.of(), List.of(), Set.of(), List.of(),
                new ModpackInstance(instance.instanceId(), instance.profileId(), "Pack", "1",
                        instance.minecraftVersion(), instance.loaderName(), "project", "v1", instanceRoot), null);

        assertThrows(IOException.class, () -> new InstanceUpgradeRecoveryService(blockedRoot)
                .begin(new InstanceUpdateService.PreparedExecution(preview, List.of())));

        assertEquals("before metadata", Files.readString(profile));
        assertEquals("before mod", Files.readString(mod));
    }

    @Test
    void completionReleasesTheRecoveryLeaseAndKeepsTheFinalSnapshot() throws Exception {
        Path game = tempDir.resolve("game");
        Path instanceRoot = game.resolve("versions/guarded");
        Path metadataRoot = tempDir.resolve("metadata");
        Path metadata = metadataRoot.resolve("guarded/guarded.json");
        Files.createDirectories(instanceRoot);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "before");
        TestInstance instance = new TestInstance(UUID.randomUUID(), "guarded", instanceRoot);
        InstanceUpgradeRecoveryService service = new InstanceUpgradeRecoveryService(tempDir.resolve("recovery"));
        InstanceUpgradeRecoveryService competing = new InstanceUpgradeRecoveryService(tempDir.resolve("recovery"));
        var execution = new InstanceUpdateService.PreparedExecution(preview(instance, metadataRoot, game), List.of());
        var draft = service.begin(execution);
        try {
            Files.writeString(metadata, "after");
            assertThrows(IOException.class, () -> competing.inspect("guarded", instance, metadataRoot, game));
            assertThrows(IOException.class, () -> competing.begin(execution));
            assertTrue(Files.isDirectory(draft.directory()));
            service.complete(draft);
            assertFalse(Files.exists(draft.directory()));
            assertEquals(List.of("metadata:guarded"), competing.inspect("guarded", instance, metadataRoot, game).files());
        } finally {
            draft.close();
        }
    }

    private static InstanceUpdateService.Preview preview(TestInstance instance,
                                                         Path metadataRoot, Path gameRoot) {
        return new InstanceUpdateService.Preview(instance, Instant.now(), metadataRoot, gameRoot,
                "fingerprint", null, List.of(), List.of(), Set.of(), List.of(),
                new ModpackInstance(instance.instanceId(), instance.profileId(), "Test pack", "1",
                        instance.minecraftVersion(), instance.loaderName(), "project", "version-1",
                        instance.gameDirectory()), null);
    }

    private static String manifest(String version, Map<String, Path> paths) throws Exception {
        StringBuilder files = new StringBuilder();
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            if (!files.isEmpty()) files.append(',');
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                    .digest(Files.readAllBytes(entry.getValue())));
            files.append("{\"path\":\"").append(entry.getKey()).append("\",\"sha512\":\"")
                    .append(hash).append("\"}");
        }
        return "{\"formatVersion\":1,\"packVersion\":\"" + version
                + "\",\"files\":[" + files + "]}";
    }

    private record TestInstance(UUID instanceId, String profileId,
                                Path gameDirectory) implements ModInstanceContext {
        @Override public String minecraftVersion() { return "1.20.1"; }
        @Override public ModLoader loader() { return ModLoader.FABRIC; }
        @Override public Path modsDirectory() { return gameDirectory.resolve("mods"); }
    }
}
