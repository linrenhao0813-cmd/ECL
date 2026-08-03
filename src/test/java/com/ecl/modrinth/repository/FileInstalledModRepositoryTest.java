package com.ecl.modrinth.repository;

import com.ecl.modrinth.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileInstalledModRepositoryTest {
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
