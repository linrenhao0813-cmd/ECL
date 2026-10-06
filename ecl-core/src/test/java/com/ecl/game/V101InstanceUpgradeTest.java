package com.ecl.game;

import com.ecl.backup.BackupEntry;
import com.ecl.backup.WorldBackupService;
import com.ecl.launch.GameProcessMarker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises the V1.0.1 profile shape and a synthetic NBT world through current services. */
class V101InstanceUpgradeTest {
    @Test
    void preservesLegacyProfileWorldAndLanSidecarAcrossEditRestoreAndReopen(@TempDir Path root) throws Exception {
        Path game = root.resolve("game");
        Path instance = Files.createDirectories(game.resolve("versions/1.21.1"));
        Files.writeString(instance.resolve("1.21.1.json"), """
                {"id":"1.21.1","type":"release","mainClass":"net.minecraft.client.main.Main"}
                """);
        InstanceLaunchProfileStore profiles = new InstanceLaunchProfileStore();
        Path profile = profiles.profileFile(instance);
        Files.createDirectories(profile.getParent());
        String legacy = """
                {"schemaVersion":1,"javaMode":"CUSTOM","javaPath":"C:\\\\Java21\\\\bin\\\\javaw.exe",
                 "performancePreset":"BALANCED","memoryMode":"CUSTOM","maxMemoryMb":4096,
                 "generatedJvmOptions":true,"customJvmArguments":["-XX:+UseG1GC"],
                 "autoRepair":true,"backupPolicyId":"default"}
                """;
        Files.writeString(profile, legacy);
        Path world = Files.createDirectories(instance.resolve("saves/世界 😀"));
        Path level = world.resolve("level.dat");
        WorldSaveService.NbtIo.Compound data = new WorldSaveService.NbtIo.Compound();
        data.put("LevelName", new WorldSaveService.NbtIo.StringValue("世界 😀"));
        data.put("GameType", new WorldSaveService.NbtIo.IntValue(0));
        data.put("Difficulty", new WorldSaveService.NbtIo.ByteValue((byte) 2));
        data.put("allowCommands", new WorldSaveService.NbtIo.ByteValue((byte) 0));
        WorldSaveService.NbtIo.Compound nbt = new WorldSaveService.NbtIo.Compound();
        nbt.put("Data", data);
        WorldSaveService.NbtIo.write(level, nbt);
        Path sidecar = world.resolve(".ecl/world-settings.json");
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"openToLan\":true,\"lanPort\":25566}");
        byte[] sidecarBytes = Files.readAllBytes(sidecar);
        byte[] originalHash = sha256(level);
        DefaultGameRepository repository = new DefaultGameRepository(game.resolve("versions"), game);
        assertEquals(instance, repository.runDirectory("1.21.1"), "legacy saves retain their isolated directory");
        InstanceLaunchProfile loaded = profiles.load(instance);
        assertEquals(4096, loaded.maxMemoryMb());
        assertEquals(List.of("-XX:+UseG1GC"), loaded.customJvmArguments());
        assertEquals(legacy, Files.readString(profile), "reading must not rewrite retired profile fields");
        WorldSaveService worlds = new WorldSaveService();
        WorldSave save = worlds.scan(repository).getFirst();
        WorldBackupService backups = new WorldBackupService(root.resolve("backups"));
        BackupEntry backup = backups.createBackup("1.21.1", "1.21.1", instance, Set.of(BackupEntry.Content.SAVES), null);
        worlds.update(save, new WorldSaveSettings(WorldSaveSettings.Difficulty.HARD, WorldSaveSettings.GameMode.CREATIVE, true));
        assertEquals("世界 😀", WorldSaveService.NbtIo.read(level).compound("Data").stringValue("LevelName", ""));
        assertArrayEquals(sidecarBytes, Files.readAllBytes(sidecar), "retired LAN settings must not be overwritten");
        new WorldBackupService(root.resolve("backups")).restore(backup, instance, null);
        assertArrayEquals(originalHash, sha256(level));
        assertArrayEquals(sidecarBytes, Files.readAllBytes(sidecar));
        GameProcessMarker.record(instance, ProcessHandle.current());
        try {
            WorldSave reopened = new WorldSaveService().scan(new DefaultGameRepository(game.resolve("versions"), game)).getFirst();
            assertThrows(java.io.IOException.class, () -> new WorldSaveService().update(reopened, WorldSaveSettings.defaults()));
            assertArrayEquals(originalHash, sha256(level));
        } finally {
            GameProcessMarker.clear(instance, ProcessHandle.current());
        }
        assertEquals(legacy, Files.readString(profile));
    }

    private static byte[] sha256(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
