package com.ecl.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultGameRepositoryTest {
    @TempDir
    Path temp;

    @Test
    void listsOnlyCompleteVersionsAndResolvesIsolationPaths() throws Exception {
        Path versions = Files.createDirectories(temp.resolve("versions"));
        Path game = Files.createDirectories(temp.resolve("game"));
        Path ready = Files.createDirectories(versions.resolve("1.21"));
        Files.writeString(ready.resolve("1.21.json"), "{\"id\":\"1.21\",\"mainClass\":\"Main\"}");
        Files.createDirectories(versions.resolve("partial"));
        DefaultGameRepository repository = new DefaultGameRepository(versions, game);

        assertEquals(List.of("1.21"), repository.installedVersions());
        assertEquals(game.resolve("versions/1.21"), repository.instanceDirectory(
                "1.21", InstanceIsolation.VERSION_ISOLATED, null));
        assertEquals(game, repository.instanceDirectory("1.21", InstanceIsolation.GLOBAL_SHARED, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.instanceDirectory("../escape", InstanceIsolation.VERSION_ISOLATED, null));
    }

    @Test
    void moddedPolicySharesVanillaAndIsolatesDetectedLoaders() throws Exception {
        Path versions = Files.createDirectories(temp.resolve("metadata"));
        Path game = Files.createDirectories(temp.resolve("game"));
        writeVersion(versions, "vanilla", """
                {"id":"vanilla","mainClass":"net.minecraft.client.main.Main"}
                """);
        writeVersion(versions, "fabric", """
                {"id":"fabric","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "libraries":[{"name":"net.fabricmc:fabric-loader:0.16.10",
                 "downloads":{"artifact":{"path":"fabric.jar","url":"https://example/fabric.jar"}}}]}
                """);
        DefaultGameRepository repository = new DefaultGameRepository(
                versions, game, DefaultIsolationType.MODDED);

        assertEquals(game, repository.runDirectory("vanilla"));
        assertEquals(game.resolve("versions/fabric"), repository.runDirectory("fabric"));
    }

    @Test
    void explicitInstanceDirectoryOverridesTheDefaultPolicy() throws Exception {
        Path versions = Files.createDirectories(temp.resolve("metadata"));
        Path game = Files.createDirectories(temp.resolve("game"));
        writeVersion(versions, "vanilla", "{\"id\":\"vanilla\",\"mainClass\":\"Main\"}");
        DefaultGameRepository repository = new DefaultGameRepository(
                versions, game, DefaultIsolationType.NEVER);

        repository.setIsolated("vanilla");
        assertEquals(game.resolve("versions/vanilla"), repository.runDirectory("vanilla"));

        Path custom = temp.resolve("custom-run");
        repository.setCustomRunDirectory("vanilla", custom);
        assertEquals(custom.toAbsolutePath().normalize(), repository.runDirectory("vanilla"));

        repository.applyDefaultIsolationSettingForNewInstance("vanilla");
        assertEquals(custom.toAbsolutePath().normalize(), repository.runDirectory("vanilla"),
                "reinstall must preserve an explicit instance override");

        repository.inheritRunDirectoryPolicy("vanilla");
        assertEquals(game, repository.runDirectory("vanilla"));
    }

    @Test
    void modpacksAreAlwaysIsolated() throws Exception {
        Path versions = Files.createDirectories(temp.resolve("metadata"));
        Path game = Files.createDirectories(temp.resolve("game"));
        writeVersion(versions, "pack", """
                {"id":"pack","mainClass":"Main","eclModpackName":"Example Pack"}
                """);
        DefaultGameRepository repository = new DefaultGameRepository(
                versions, game, DefaultIsolationType.NEVER);

        assertEquals(game.resolve("versions/pack"), repository.runDirectory("pack"));
    }

    @Test
    void preservesLegacyVanillaDataUntilUserExplicitlyFollowsPolicy() throws Exception {
        Path versions = Files.createDirectories(temp.resolve("metadata"));
        Path game = Files.createDirectories(temp.resolve("game"));
        writeVersion(versions, "vanilla", "{\"id\":\"vanilla\",\"mainClass\":\"Main\"}");
        Path legacyRoot = Files.createDirectories(game.resolve("versions/vanilla/saves"));
        DefaultGameRepository repository = new DefaultGameRepository(
                versions, game, DefaultIsolationType.MODDED);

        assertEquals(legacyRoot.getParent(), repository.runDirectory("vanilla"));

        repository.inheritRunDirectoryPolicy("vanilla");
        assertEquals(game, repository.runDirectory("vanilla"));
    }

    private static void writeVersion(Path versions, String id, String json) throws Exception {
        Path directory = Files.createDirectories(versions.resolve(id));
        Files.writeString(directory.resolve(id + ".json"), json);
    }
}
