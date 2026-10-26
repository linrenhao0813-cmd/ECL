package com.ecl.backup;

import com.ecl.util.GsonProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldBackupMetadataTest {
    @TempDir
    Path directory;

    @Test
    void rejectsOversizedMetadataBeforeParsing() throws Exception {
        WorldBackupMetadata metadata = new WorldBackupMetadata(directory);
        Path archive = archive(metadata);
        Files.writeString(metadata.metadataPath(archive), json("saves/world/level.dat") + " ".repeat(16 * 1024 * 1024));
        assertThrows(IOException.class, () -> metadata.readEntry(archive, "profile"));
    }

    @Test
    void invalidFileListsFailWithAnIoError() throws Exception {
        WorldBackupMetadata metadata = new WorldBackupMetadata(directory);
        Path archive = archive(metadata);
        var json = com.google.gson.JsonParser.parseString(json("saves/world/level.dat")).getAsJsonObject();
        json.getAsJsonArray("files").add(com.google.gson.JsonNull.INSTANCE);
        Files.writeString(metadata.metadataPath(archive), json.toString());
        assertThrows(IOException.class, () -> metadata.readEntry(archive, "profile"));
    }

    @Test
    void rejectsUnsafeMetadataPathsBeforeExtraction() throws Exception {
        WorldBackupMetadata metadata = new WorldBackupMetadata(directory);
        Path archive = archive(metadata);
        for (String name : List.of("../outside", "C:/outside", "saves/file:stream", "mods/unlisted", "saves/../../outside")) {
            Files.writeString(metadata.metadataPath(archive), json(name));
            assertThrows(IOException.class, () -> metadata.readEntry(archive, "profile"), name);
        }
    }

    private Path archive(WorldBackupMetadata metadata) throws IOException {
        Path root = Files.createDirectories(metadata.backupDirectory("profile"));
        return Files.write(root.resolve("backup.zip"), new byte[0]);
    }

    private String json(String path) {
        return GsonProvider.compact().toJson(new WorldBackupMetadata.BackupMetadata(1, "profile", "1.21",
                "2026-10-26T00:00:00Z", List.of("saves"), 1, List.of(new BackupEntry.FileEntry(path, 1))));
    }
}
