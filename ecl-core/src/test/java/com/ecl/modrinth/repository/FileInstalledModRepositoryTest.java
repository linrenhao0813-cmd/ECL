package com.ecl.modrinth.repository;

import com.ecl.modrinth.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import com.ecl.modrinth.model.InstalledMod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileInstalledModRepositoryTest {
    @Test
    void migratesLegacyOwnerAndPreservesAllOwnersOnSubsequentWrites(@TempDir Path gameDirectory) throws Exception {
        var instance = TestFixtures.instance(gameDirectory);
        Files.writeString(gameDirectory.resolve("launcher-mods.json"), """
                {"schemaVersion":1,"mods":[{"instanceId":"%s","projectId":"shared",
                "relativePath":"mods/shared.jar","requiredByProjectId":"first","enabled":true}]}
                """.formatted(instance.instanceId()));
        var repository = new FileInstalledModRepository();
        InstalledMod legacy = repository.findAll(instance).getFirst();
        assertEquals(Set.of("first"), legacy.requiredByProjectIds());
        assertFalse(legacy.dependencyMetadataKnown());

        repository.saveAll(instance, List.of(legacy.withRequiredByProjectIds(Set.of("first", "second"))));
        InstalledMod restored = repository.findAll(instance).getFirst();
        assertEquals(Set.of("first", "second"), restored.requiredByProjectIds());
        assertFalse(restored.dependencyMetadataKnown());
        repository.saveAll(instance, List.of(restored.withRequiredByProjectIds(Set.of())));
        assertEquals("", repository.findAll(instance).getFirst().requiredByProjectId());
    }

    @Test
    void rejectsEntriesWithMissingInstanceId(@TempDir Path gameDirectory) throws Exception {
        var instance = TestFixtures.instance(gameDirectory);
        Files.writeString(gameDirectory.resolve("launcher-mods.json"), """
                {
                  "schemaVersion": 1,
                  "mods": [
                    {
                      "relativePath": "mods/example.jar"
                    }
                  ]
                }
                """);

        IOException error = assertThrows(
                IOException.class,
                () -> new FileInstalledModRepository().findAll(instance));

        assertTrue(error.getMessage().contains("Invalid installed mod index"));
        assertTrue(error.getCause().getMessage().contains("instanceId is missing"));
    }

    @Test
    void rejectsEntriesWhosePathEscapesTheInstance(@TempDir Path gameDirectory) throws Exception {
        var instance = TestFixtures.instance(gameDirectory);
        String instanceId = instance.instanceId().toString();
        Files.writeString(gameDirectory.resolve("launcher-mods.json"), """
                {
                  "schemaVersion": 1,
                  "mods": [
                    {
                      "instanceId": "%s",
                      "relativePath": "../outside.jar"
                    }
                  ]
                }
                """.formatted(instanceId));

        IOException error = assertThrows(
                IOException.class,
                () -> new FileInstalledModRepository().findAll(instance));

        assertTrue(error.getCause().getMessage().contains("escapes the instance"));
    }
}
