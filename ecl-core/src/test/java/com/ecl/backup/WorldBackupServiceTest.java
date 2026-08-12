package com.ecl.backup;

import com.ecl.util.FileUtil;
import com.ecl.util.ZipUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBackupServiceTest {
    private static final String PROFILE = "fabric-loader-0.16.10-1.21.4";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsListsAndRestoresWorldFilesWithoutChangingExcludedContent() throws Exception {
        Path instance = temporaryDirectory.resolve("game/versions").resolve(PROFILE);
        Path levelDat = instance.resolve("saves/Test World/level.dat");
        Path region = instance.resolve("saves/Test World/region/r.0.0.mca");
        Path config = instance.resolve("config/example.toml");
        Path mod = instance.resolve("mods/current.jar");
        write(levelDat, "level-before-backup");
        write(region, "region-before-backup");
        write(config, "enabled=true");
        write(mod, "leave-this-mod-alone");
        String expectedLevelSha1 = FileUtil.sha1(levelDat.toFile());
        String expectedRegionSha1 = FileUtil.sha1(region.toFile());

        WorldBackupService service = service();
        BackupEntry created = service.createBackup(PROFILE, "1.21.4", instance,
                EnumSet.of(BackupEntry.Content.SAVES, BackupEntry.Content.CONFIG), null);

        assertTrue(Files.isRegularFile(created.archivePath()));
        assertTrue(Files.isRegularFile(created.metadataPath()));
        assertEquals("backup-" + PROFILE + "-20260718-150405.zip",
                created.archivePath().getFileName().toString());
        assertEquals(EnumSet.of(BackupEntry.Content.SAVES, BackupEntry.Content.CONFIG),
                created.includedContent());
        assertTrue(created.files().stream().anyMatch(file -> file.path().equals(
                "saves/Test World/level.dat")));

        List<BackupEntry> listed = service.listBackups(PROFILE);
        assertEquals(1, listed.size());
        assertEquals(created.archivePath(), listed.getFirst().archivePath());
        assertEquals("1.21.4", listed.getFirst().sourceVersion());

        write(levelDat, "damaged-level");
        Files.delete(region);
        write(instance.resolve("saves/New World/new.dat"), "remove-on-restore");
        write(config, "enabled=false");
        write(mod, "mod-was-updated");

        service.restore(listed.getFirst(), instance, null);

        assertEquals(expectedLevelSha1, FileUtil.sha1(levelDat.toFile()));
        assertEquals(expectedRegionSha1, FileUtil.sha1(region.toFile()));
        assertEquals("enabled=true", Files.readString(config));
        assertFalse(Files.exists(instance.resolve("saves/New World")));
        assertEquals("mod-was-updated", Files.readString(mod),
                "directories excluded from a backup must not be replaced");
        assertNoRestoreWorkspace(instance.getParent());
    }

    @Test
    void includesSavesEvenWhenCallerProvidesNoOptionalContent() throws Exception {
        Path instance = temporaryDirectory.resolve("instance");
        write(instance.resolve("saves/world/data.bin"), "world");

        BackupEntry backup = service().createBackup(PROFILE, PROFILE, instance,
                EnumSet.noneOf(BackupEntry.Content.class), null);

        assertEquals(EnumSet.of(BackupEntry.Content.SAVES), backup.includedContent());
        assertTrue(backup.files().stream().allMatch(file -> file.path().startsWith("saves/")));
    }

    @Test
    void prunesOldBackupsAndTheirMetadata() throws Exception {
        Path instance = temporaryDirectory.resolve("instance");
        write(instance.resolve("saves/world/level.dat"), "world");
        WorldBackupService service = service();
        for (int index = 0; index < 3; index++) {
            write(instance.resolve("saves/world/level.dat"), "world-" + index);
            service.createBackup(PROFILE, "1.21.4", instance,
                    EnumSet.of(BackupEntry.Content.SAVES), null);
        }

        List<BackupEntry> removed = service.prune(PROFILE, 1);

        assertEquals(2, removed.size());
        assertEquals(1, service.listBackups(PROFILE).size());
        for (BackupEntry backup : removed) {
            assertFalse(Files.exists(backup.archivePath()));
            assertFalse(Files.exists(backup.metadataPath()));
        }
    }

    @Test
    void rejectsZipSlipEntriesWithoutWritingOutsideDestination() throws Exception {
        Path archive = temporaryDirectory.resolve("malicious.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("owned".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path destination = temporaryDirectory.resolve("extract");

        IOException error = assertThrows(IOException.class,
                () -> ZipUtil.extractSafely(archive, destination, null));

        assertTrue(error.getMessage().contains("escapes"));
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped.txt")));
    }

    @Test
    void failedValidationLeavesCurrentWorldUntouched() throws Exception {
        Path instance = temporaryDirectory.resolve("instance");
        Path levelDat = instance.resolve("saves/world/level.dat");
        write(levelDat, "world");
        WorldBackupService service = service();
        BackupEntry backup = service.createBackup(PROFILE, "1.21.4", instance,
                EnumSet.of(BackupEntry.Content.SAVES), null);
        write(levelDat, "current-world-must-survive");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup.archivePath()))) {
            zip.putNextEntry(new ZipEntry("saves/world/level.dat"));
            zip.write("world".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("saves/unexpected.dat"));
            zip.write("unexpected".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(IOException.class, () -> service.restore(backup, instance, null));
        assertEquals("current-world-must-survive", Files.readString(levelDat));
        assertNoRestoreWorkspace(instance.getParent());
    }

    private WorldBackupService service() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-18T07:04:05Z"),
                ZoneId.of("Asia/Shanghai"));
        return new WorldBackupService(temporaryDirectory.resolve("backups"), clock);
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void assertNoRestoreWorkspace(Path parent) throws IOException {
        try (var children = Files.list(parent)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".ecl-restore-")));
        }
    }
}
