package com.ecl.modrinth.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionProfileModInstanceContextTest {
    @Test
    void adaptsLoaderProfileMetadataAndUsesProfileSpecificGameDirectory(@TempDir Path tempDir) throws Exception {
        Path metadata = tempDir.resolve("ecl-versions");
        Path gameRoot = tempDir.resolve("minecraft");
        writeProfile(metadata, "1.21.1", """
                {"id":"1.21.1","mainClass":"net.minecraft.client.main.Main"}
                """);
        writeProfile(metadata, "fabric-loader-0.16.9-1.21.1", """
                {
                  "id":"fabric-loader-0.16.9-1.21.1",
                  "inheritsFrom":"1.21.1",
                  "eclMinecraftVersion":"1.21.1",
                  "eclModLoader":"fabric",
                  "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient"
                }
                """);

        VersionProfileModInstanceContext context = VersionProfileModInstanceContext.load(
                "fabric-loader-0.16.9-1.21.1", metadata, gameRoot);

        assertEquals("1.21.1", context.minecraftVersion());
        assertEquals(ModLoader.FABRIC, context.loader());
        assertEquals(gameRoot.resolve("versions/fabric-loader-0.16.9-1.21.1").toAbsolutePath().normalize(),
                context.gameDirectory());
        assertEquals(context.gameDirectory().resolve("mods"), context.modsDirectory());
    }

    @Test
    void resolvesMinecraftVersionThroughInheritanceAndDetectsLoaderFromLibraries(@TempDir Path tempDir)
            throws Exception {
        Path metadata = tempDir.resolve("ecl-versions");
        Path gameRoot = tempDir.resolve("minecraft");
        writeProfile(metadata, "1.20.1", "{\"id\":\"1.20.1\"}");
        writeProfile(metadata, "custom-profile", """
                {
                  "id":"custom-profile",
                  "inheritsFrom":"1.20.1",
                  "libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.3.0"}]
                }
                """);

        VersionProfileModInstanceContext first =
                VersionProfileModInstanceContext.load("custom-profile", metadata, gameRoot);
        VersionProfileModInstanceContext second =
                VersionProfileModInstanceContext.load("custom-profile", metadata, gameRoot);
        VersionProfileModInstanceContext vanilla =
                VersionProfileModInstanceContext.load("1.20.1", metadata, gameRoot);

        assertEquals("1.20.1", first.minecraftVersion());
        assertEquals(ModLoader.FORGE, first.loader());
        assertEquals(first.instanceId(), second.instanceId());
        assertNotEquals(first.instanceId(), vanilla.instanceId());
        assertTrue(first.loader().supportsMods());
    }

    @Test
    void distinguishesNeoForgeFromForgeInMainClassAndLibraryCoordinates(@TempDir Path tempDir)
            throws Exception {
        Path metadata = tempDir.resolve("ecl-versions");
        Path gameRoot = tempDir.resolve("minecraft");
        writeProfile(metadata, "neoforge-main", """
                {
                  "id":"neoforge-main",
                  "eclMinecraftVersion":"1.21.1",
                  "mainClass":"net.neoforged.fml.loading.targets.CommonLaunchHandler"
                }
                """);
        writeProfile(metadata, "neoforge-library", """
                {
                  "id":"neoforge-library",
                  "eclMinecraftVersion":"1.21.1",
                  "libraries":[{"name":"net.neoforged:neoforge:21.1.200"}]
                }
                """);
        writeProfile(metadata, "forge-library", """
                {
                  "id":"forge-library",
                  "eclMinecraftVersion":"1.21.1",
                  "libraries":[{"name":"net.minecraftforge:forge:1.21.1-52.0.1"}]
                }
                """);

        assertEquals(ModLoader.NEOFORGE,
                VersionProfileModInstanceContext.load("neoforge-main", metadata, gameRoot).loader());
        assertEquals(ModLoader.NEOFORGE,
                VersionProfileModInstanceContext.load("neoforge-library", metadata, gameRoot).loader());
        assertEquals(ModLoader.FORGE,
                VersionProfileModInstanceContext.load("forge-library", metadata, gameRoot).loader());
    }

    private static void writeProfile(Path metadataRoot, String id, String json) throws Exception {
        Path directory = metadataRoot.resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(id + ".json"), json);
    }
}
