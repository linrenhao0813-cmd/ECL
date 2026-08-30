package com.ecl.game;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSaveServiceTest {
    @Test
    void scansByInstanceMetadataAndUpdatesLevelDatAndLanPreference() throws Exception {
        Path root = Files.createTempDirectory("ecl-world-saves-");
        Path versions = Files.createDirectories(root.resolve("versions/1.20.1"));
        Files.writeString(versions.resolve("1.20.1.json"), """
                {"id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main"}
                """);
        Path instance = Files.createDirectories(root.resolve("game/versions/1.20.1/saves/Test World"));
        WorldSaveService.NbtIo.Compound data = new WorldSaveService.NbtIo.Compound();
        data.put("GameType", new WorldSaveService.NbtIo.IntValue(0));
        data.put("Difficulty", new WorldSaveService.NbtIo.ByteValue((byte) 2));
        data.put("allowCommands", new WorldSaveService.NbtIo.ByteValue((byte) 0));
        data.put("WorldName", new WorldSaveService.NbtIo.StringValue("世界 😀"));
        WorldSaveService.NbtIo.Compound rootTag = new WorldSaveService.NbtIo.Compound();
        rootTag.put("Data", data);
        WorldSaveService.NbtIo.write(instance.resolve("level.dat"), rootTag);

        DefaultGameRepository repository = new DefaultGameRepository(versions.getParent(),
                root.resolve("game"), DefaultIsolationType.ALWAYS);
        WorldSaveService service = new WorldSaveService();
        WorldSave save = service.scan(repository).get(0);
        assertEquals("1.20.1", save.minecraftVersion());
        assertEquals("vanilla", save.modLoader());
        assertEquals(WorldSaveSettings.Difficulty.NORMAL, save.settings().difficulty());

        service.update(save, new WorldSaveSettings(WorldSaveSettings.Difficulty.HARD,
                WorldSaveSettings.GameMode.CREATIVE, true));
        WorldSave updated = service.scan(repository).get(0);
        assertEquals(WorldSaveSettings.Difficulty.HARD, updated.settings().difficulty());
        assertEquals(WorldSaveSettings.GameMode.CREATIVE, updated.settings().gameMode());
        assertTrue(updated.settings().allowCommands());
        assertEquals("世界 😀", WorldSaveService.NbtIo.read(instance.resolve("level.dat"))
                .compound("Data").stringValue("WorldName", ""));
    }

    @Test
    void rejectsInvalidLevelDatInsteadOfSavingOnlyTheSidecar() throws Exception {
        Path world = Files.createTempDirectory("ecl-invalid-world-");
        Files.writeString(world.resolve("level.dat"), "not nbt");
        WorldSave save = new WorldSave("Broken", world, "test", "1.20.1", "vanilla", "",
                0L, WorldSaveSettings.defaults());

        assertThrows(java.io.IOException.class,
                () -> new WorldSaveService().update(save, WorldSaveSettings.defaults()));
    }

    @Test
    void marksSavesInTheSharedRunDirectory() throws Exception {
        Path root = Files.createTempDirectory("ecl-shared-worlds-");
        Path versions = Files.createDirectories(root.resolve("versions/1.20.1"));
        Files.writeString(versions.resolve("1.20.1.json"), "{\"id\":\"1.20.1\"}");
        Path world = Files.createDirectories(root.resolve("game/saves/Shared World"));
        Files.writeString(world.resolve("level.dat"), "not nbt");

        DefaultGameRepository repository = new DefaultGameRepository(versions.getParent(),
                root.resolve("game"), DefaultIsolationType.NEVER);
        WorldSave save = new WorldSaveService().scan(repository).get(0);
        assertTrue(save.sharedDirectory());
        assertEquals("shared", save.groupId());
    }

    @Test
    void rejectsUnboundedNbtArrayLengths() throws Exception {
        Path levelDat = Files.createTempFile("ecl-malformed-level-", ".dat");
        try (DataOutputStream data = new DataOutputStream(
                new GZIPOutputStream(Files.newOutputStream(levelDat)))) {
            data.writeByte(10); // root compound
            data.writeShort(0); // root name
            data.writeByte(7); // byte array
            data.writeShort(4);
            data.writeBytes("evil");
            data.writeInt(Integer.MAX_VALUE);
        }

        assertThrows(java.io.IOException.class,
                () -> WorldSaveService.NbtIo.read(levelDat));
    }
}
