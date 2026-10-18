package com.ecl.backup;

import com.ecl.launch.GameProcessMarker;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBackupRecoveryTest {
    @TempDir Path root;

    @Test
    void detachedLiveGameBlocksBackupAndRestoreWithoutChangingItsWorld() throws Exception {
        Path instance = root.resolve("live");
        Path world = instance.resolve("saves/world/level.dat");
        write(world, "backup world");
        WorldBackupService service = service();
        BackupEntry backup = service.createBackup("live", "1.21.1", instance, EnumSet.of(BackupEntry.Content.SAVES), null);
        write(world, "live world");
        GameProcessMarker.record(instance, ProcessHandle.current());
        try {
            assertTrue(GameProcessMarker.isRunning(instance));
            assertThrows(IOException.class, () -> service.restore(backup, instance, null));
            assertThrows(IOException.class, () -> service.recoverInterruptedRestore(instance));
            assertThrows(IOException.class, () -> service.createBackup("live", "1.21.1", instance, null, null));
            assertEquals("live world", Files.readString(world));
        } finally {
            GameProcessMarker.clear(instance, ProcessHandle.current());
        }
        service.restore(backup, instance, null);
        assertEquals("backup world", Files.readString(world));
    }

    @Test
    void interruptedRenamesRecoverOriginalDirectoriesAndRemoveNewContent() throws Exception {
        for (int phase = 0; phase < 3; phase++) {
            Path instance = root.resolve("phase-" + phase);
            Path workspace = workspace(instance);
            write(instance.resolve("saves/world/level.dat"), "original");
            write(instance.resolve(".ecl/config/launch-profile.json"), "keep settings");
            write(workspace.resolve("content/saves/world/level.dat"), "incoming");
            write(workspace.resolve("content/mods/new.jar"), "new mod");
            Files.createDirectories(workspace.resolve("rollback"));
            journal(instance, "APPLYING");
            if (phase >= 1) Files.move(instance.resolve("saves"), workspace.resolve("rollback/saves"));
            if (phase >= 2) {
                Files.move(workspace.resolve("content/saves"), instance.resolve("saves"));
                Files.move(workspace.resolve("content/mods"), instance.resolve("mods"));
            }
            service().recoverInterruptedRestore(instance);
            assertEquals("original", Files.readString(instance.resolve("saves/world/level.dat")));
            assertEquals("keep settings", Files.readString(instance.resolve(".ecl/config/launch-profile.json")));
            assertFalse(Files.exists(instance.resolve("mods")));
            assertFalse(Files.exists(workspace));
            service().recoverInterruptedRestore(instance);
            assertEquals("original", Files.readString(instance.resolve("saves/world/level.dat")));
        }
    }

    @Test
    void recoveryIsIdempotentWhenOriginalWasAlreadyMovedBack() throws Exception {
        Path instance = root.resolve("rollback-resumed");
        write(instance.resolve("saves/world/level.dat"), "original already restored");
        journal(instance, "APPLYING");
        service().recoverInterruptedRestore(instance);
        assertEquals("original already restored", Files.readString(instance.resolve("saves/world/level.dat")));
        assertFalse(Files.exists(workspace(instance)));
    }

    @Test
    void committedRestoreKeepsReplacementAndFinishesCleanup() throws Exception {
        Path instance = root.resolve("committed");
        write(instance.resolve("saves/world/level.dat"), "restored");
        write(instance.resolve("mods/new.jar"), "restored mod");
        write(workspace(instance).resolve("rollback/saves/world/level.dat"), "old");
        journal(instance, "COMMITTED");
        service().recoverInterruptedRestore(instance);
        assertEquals("restored", Files.readString(instance.resolve("saves/world/level.dat")));
        assertEquals("restored mod", Files.readString(instance.resolve("mods/new.jar")));
        assertFalse(Files.exists(workspace(instance)));
    }

    @Test
    void missingOrForeignJournalRetainsRollbackDataAndStopsRecovery() throws Exception {
        Path instance = root.resolve("invalid");
        Path original = workspace(instance).resolve("rollback/saves/world/level.dat");
        write(original, "original");
        assertThrows(IOException.class, () -> service().recoverInterruptedRestore(instance));
        journal(instance, "APPLYING");
        Path file = workspace(instance).resolve("journal.json");
        JsonObject foreign = HttpUtil.readJson(file.toFile());
        foreign.addProperty("instanceRoot", root.resolve("other").toString());
        HttpUtil.writeJson(file.toFile(), foreign);
        assertThrows(IOException.class, () -> service().recoverInterruptedRestore(instance));
        assertEquals("original", Files.readString(original));
        assertFalse(Files.exists(instance.resolve("saves")));
    }

    private void journal(Path instance, String phase) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("instanceRoot", instance.toAbsolutePath().normalize().toString());
        json.addProperty("phase", phase);
        JsonArray entries = new JsonArray();
        for (String name : new String[]{"saves", "mods"}) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.addProperty("originalExists", name.equals("saves"));
            entries.add(entry);
        }
        json.add("entries", entries);
        HttpUtil.writeJson(workspace(instance).resolve("journal.json").toFile(), json);
    }

    private WorldBackupService service() { return new WorldBackupService(root.resolve("backups")); }
    private static Path workspace(Path instance) { return instance.resolve(".ecl/world-restore"); }
    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
