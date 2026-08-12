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
}
