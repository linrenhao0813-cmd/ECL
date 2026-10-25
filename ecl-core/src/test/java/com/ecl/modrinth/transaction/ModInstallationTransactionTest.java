package com.ecl.modrinth.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModInstallationTransactionTest {
    @TempDir Path game;

    @Test
    void invalidStructuredJournalsPreserveUniqueBackups() throws Exception {
        for (String json : java.util.List.of("{}", "{\"status\":\"APPLYING\"}",
                "{\"status\":\"APPLYING\",\"entries\":null}",
                "{\"status\":\"UNKNOWN\",\"entries\":[]}")) {
            Path transaction = Files.createDirectories(game.resolve(".ecl-mod-transactions/crashed"));
            Path backup = transaction.resolve("backups/only-copy.bak");
            Files.createDirectories(backup.getParent());
            Files.writeString(backup, "unique previous bytes");
            Path journal = Files.writeString(transaction.resolve("journal.json"), json);
            assertThrows(IOException.class, () -> FileModInstallationTransaction.recoverIncompleteTransactions(game), json);
            assertEquals("unique previous bytes", Files.readString(backup));
            assertEquals(json, Files.readString(journal));
        }
    }

    @Test
    void invalidEntriesAndUnlistedBackupsAreValidatedBeforeRollback() throws Exception {
        String valid = """
                {"status":"APPLYING","entries":[{
                  "stagedFile":".ecl-mod-transactions/crashed/download.part",
                  "finalFile":"mods/example.jar","oldFile":null,
                  "finalBackup":".ecl-mod-transactions/crashed/backups/0-final.bak",
                  "oldBackup":".ecl-mod-transactions/crashed/backups/0-old.bak",
                  "finalFileExisted":true}]}
                """;
        for (String json : java.util.List.of(
                valid.replace("true", "null"),
                valid.replace("mods/example.jar", "../outside.jar"),
                valid.replace("crashed/download.part", "other/download.part"),
                valid.replace("crashed/backups/0-final.bak", "other/backups/0-final.bak"),
                valid.replace("APPLYING", "APPLIED"))) {
            Path transaction = Files.createDirectories(game.resolve(".ecl-mod-transactions/crashed/backups")).getParent();
            Path backup = Files.writeString(transaction.resolve("backups/0-final.bak"), "original");
            Path unlisted = transaction.resolve("backups/unlisted.bak");
            Files.deleteIfExists(unlisted);
            if (json.contains("APPLIED")) Files.writeString(unlisted, "unique copy");
            Path target = game.resolve("mods/example.jar");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "installed");
            Path journal = Files.writeString(transaction.resolve("journal.json"), json);
            assertThrows(IOException.class, () -> FileModInstallationTransaction.recoverIncompleteTransactions(game), json);
            assertEquals("installed", Files.readString(target));
            assertEquals("original", Files.readString(backup));
            if (json.contains("APPLIED")) assertEquals("unique copy", Files.readString(unlisted));
            assertEquals(json, Files.readString(journal));
        }
    }

    @Test
    void orphanOldBackupWithoutOldFileIsPreserved() throws Exception {
        Path transaction = Files.createDirectories(game.resolve(".ecl-mod-transactions/crashed/backups")).getParent();
        Path backup = Files.writeString(transaction.resolve("backups/0-old.bak"), "unique original");
        String json = """
                {"status":"APPLYING","entries":[{
                  "stagedFile":".ecl-mod-transactions/crashed/download.part",
                  "finalFile":"mods/example.jar","oldFile":null,
                  "finalBackup":".ecl-mod-transactions/crashed/backups/0-final.bak",
                  "oldBackup":".ecl-mod-transactions/crashed/backups/0-old.bak",
                  "finalFileExisted":false}]}
                """;
        Files.writeString(transaction.resolve("journal.json"), json);
        assertThrows(IOException.class, () -> FileModInstallationTransaction.recoverIncompleteTransactions(game));
        assertEquals("unique original", Files.readString(backup));
    }

    @Test
    void closingWithDamagedStructuredJournalPreservesUniqueBackup() throws Exception {
        Path backup;
        try (var transaction = new FileModInstallationTransaction(game)) {
            backup = Files.writeString(transaction.temporaryDirectory().resolve("backups/only-copy.bak"), "only copy");
            Files.writeString(transaction.temporaryDirectory().resolve("journal.json"), "{}");
        }
        assertEquals("only copy", Files.readString(backup));
    }

    @Test
    void closingWithoutJournalPreservesUniqueBackup() throws Exception {
        Path backup;
        try (var transaction = new FileModInstallationTransaction(game)) {
            backup = Files.writeString(transaction.temporaryDirectory().resolve("backups/only-copy.bak"), "only copy");
        }
        assertEquals("only copy", Files.readString(backup));
    }

    @Test
    void missingJournalPreservesUniqueBackupsAndUnknownContents() throws Exception {
        Path transaction = Files.createDirectories(game.resolve(".ecl-mod-transactions/crashed"));
        Path backup = transaction.resolve("backups/only-copy.bak");
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "unique previous bytes");
        assertThrows(IOException.class, () -> FileModInstallationTransaction.recoverIncompleteTransactions(game));
        assertEquals("unique previous bytes", Files.readString(backup));
        Files.delete(backup);
        Path unknown = Files.writeString(transaction.resolve("unknown.bin"), "unclassified bytes");
        assertThrows(IOException.class, () -> FileModInstallationTransaction.recoverIncompleteTransactions(game));
        assertEquals("unclassified bytes", Files.readString(unknown));
    }

    @Test
    void runningMarkerPreventsOpeningTransactionAndReleasesLock() throws Exception {
        com.ecl.launch.GameProcessMarker.record(game, ProcessHandle.current());
        assertThrows(IOException.class, () -> {
            try (var ignored = new FileModInstallationTransaction(game)) {
                fail("must reject a running game");
            }
        });
        com.ecl.launch.GameProcessMarker.clear(game, ProcessHandle.current());
        try (var transaction = new FileModInstallationTransaction(game)) {
            transaction.commit();
        }
    }

    @Test
    void runningMarkerPreventsRecoveryFromChangingFiles() throws Exception {
        Path target = game.resolve("mods/existing.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "installed");
        Path transaction = game.resolve(".ecl-mod-transactions/crashed");
        Files.createDirectories(transaction.resolve("backups"));
        Files.writeString(transaction.resolve("backups/0-final.bak"), "previous");
        String journal = """
                {"status":"APPLYING","entries":[{
                  "stagedFile":".ecl-mod-transactions/crashed/missing.part",
                  "finalFile":"mods/existing.jar","oldFile":null,
                  "finalBackup":".ecl-mod-transactions/crashed/backups/0-final.bak",
                  "oldBackup":".ecl-mod-transactions/crashed/backups/0-old.bak",
                  "finalFileExisted":true}]}
                """;
        Files.writeString(transaction.resolve("journal.json"), journal);
        com.ecl.launch.GameProcessMarker.record(game, ProcessHandle.current());

        assertThrows(IOException.class, () -> FileModInstallationTransaction.recoverIncompleteTransactions(game));
        assertEquals("installed", Files.readString(target));
        assertEquals(journal, Files.readString(transaction.resolve("journal.json")));
        assertEquals("previous", Files.readString(transaction.resolve("backups/0-final.bak")));

        com.ecl.launch.GameProcessMarker.clear(game, ProcessHandle.current());
        FileModInstallationTransaction.recoverIncompleteTransactions(game);
        assertEquals("previous", Files.readString(target));
    }

    @Test
    void commitsAllStagedFiles() throws Exception {
        Path target = game.resolve("mods/example.jar");
        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            Path staged = Files.writeString(transaction.temporaryDirectory().resolve("download.part"), "new");
            transaction.stageDownloadedFile(staged, target);
            transaction.commit();
        }
        assertEquals("new", Files.readString(target));
    }

    @Test
    void closeWithoutCommitRollsBack() throws Exception {
        Path target = game.resolve("mods/example.jar");
        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            Path staged = Files.writeString(transaction.temporaryDirectory().resolve("download.part"), "new");
            transaction.stageDownloadedFile(staged, target);
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void failedCommitRestoresReplacedFileAndEarlierTargets() throws Exception {
        Path old = game.resolve("mods/old.jar");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "old");
        Path firstTarget = game.resolve("mods/first.jar");
        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            Path first = Files.writeString(transaction.temporaryDirectory().resolve("first.part"), "first");
            Path replacement = Files.writeString(transaction.temporaryDirectory().resolve("replacement.part"), "new");
            Path missing = transaction.temporaryDirectory().resolve("missing.part");
            transaction.stageDownloadedFile(first, firstTarget);
            transaction.stageReplacement(old, replacement, old);
            transaction.stageDownloadedFile(missing, game.resolve("mods/never.jar"));
            assertThrows(IOException.class, transaction::commit);
        }
        assertEquals("old", Files.readString(old));
        assertFalse(Files.exists(firstTarget));
    }

    @Test
    void failedCommitPreservesExistingTargetThatWasNeverApplied() throws Exception {
        Path target = game.resolve("mods/existing.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "original");

        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            Path missing = transaction.temporaryDirectory().resolve("missing.part");
            transaction.stageDownloadedFile(missing, target);
            assertThrows(IOException.class, transaction::commit);
        }

        assertEquals("original", Files.readString(target));
    }

    @Test
    void rejectsEscapingAndDuplicateTargets() throws Exception {
        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            Path staged = Files.writeString(transaction.temporaryDirectory().resolve("one.part"), "one");
            Path target = game.resolve("mods/one.jar");
            transaction.stageDownloadedFile(staged, target);
            assertThrows(IllegalArgumentException.class,
                    () -> transaction.stageDownloadedFile(staged, target));
            assertThrows(IllegalArgumentException.class,
                    () -> transaction.stageDownloadedFile(staged, game.resolveSibling("escape.jar")));
        }
    }

    @Test
    void recoverySkipsTransactionWhoseLockIsStillHeld() throws Exception {
        FileModInstallationTransaction active = new FileModInstallationTransaction(game);
        Path directory = active.temporaryDirectory();
        Files.writeString(directory.resolve("orphan.part"), "orphan");

        FileModInstallationTransaction.recoverIncompleteTransactions(game);

        assertTrue(Files.exists(directory));
        active.close();
        assertFalse(Files.exists(directory));
    }

    @Test
    void recoveryPreservesExistingTargetWithoutBackup() throws Exception {
        Path target = game.resolve("mods/existing.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "original");
        Path transaction = game.resolve(".ecl-mod-transactions/crashed");
        Files.createDirectories(transaction.resolve("backups"));
        Files.writeString(transaction.resolve("journal.json"), """
                {
                  "status": "APPLYING",
                  "entries": [{
                    "stagedFile": ".ecl-mod-transactions/crashed/missing.part",
                    "finalFile": "mods/existing.jar",
                    "oldFile": null,
                    "finalBackup": ".ecl-mod-transactions/crashed/backups/0-final.bak",
                    "oldBackup": ".ecl-mod-transactions/crashed/backups/0-old.bak",
                    "finalFileExisted": true
                  }]
                }
                """);

        FileModInstallationTransaction.recoverIncompleteTransactions(game);

        assertEquals("original", Files.readString(target));
        assertFalse(Files.exists(transaction));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void committedFilesSurviveFailureToCleanTemporaryDirectory() throws Exception {
        Path existing = game.resolve("mods/existing.jar");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "old");
        Path added = game.resolve("mods/added.jar");
        Path transactionDirectory;

        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            transactionDirectory = transaction.temporaryDirectory();
            Path stagedAdded = Files.writeString(
                    transactionDirectory.resolve("added.part"), "added");
            Path stagedExisting = Files.writeString(
                    transactionDirectory.resolve("existing.part"), "replaced");
            transaction.stageDownloadedFile(stagedAdded, added);
            transaction.stageReplacement(existing, stagedExisting, existing);
            try (DirectoryInUse ignored = DirectoryInUse.acquire(transactionDirectory)) {
                assertDoesNotThrow(transaction::commit);
            }
            assertTrue(Files.exists(transactionDirectory));
        }

        assertEquals("added", Files.readString(added));
        assertEquals("replaced", Files.readString(existing));
        FileModInstallationTransaction.recoverIncompleteTransactions(game);
        assertFalse(Files.exists(transactionDirectory));
        assertEquals("added", Files.readString(added));
        assertEquals("replaced", Files.readString(existing));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unfinishedTransactionIsDiscardedAfterCleanupFailure() throws Exception {
        Path target = game.resolve("mods/example.jar");
        Path transactionDirectory;
        try (FileModInstallationTransaction transaction = new FileModInstallationTransaction(game)) {
            transactionDirectory = transaction.temporaryDirectory();
            Path staged = Files.writeString(transactionDirectory.resolve("download.part"), "new");
            transaction.stageDownloadedFile(staged, target);
            try (DirectoryInUse ignored = DirectoryInUse.acquire(transactionDirectory)) {
                transaction.close();
                assertTrue(Files.exists(transactionDirectory));
            }
        }

        assertFalse(Files.exists(target));
        FileModInstallationTransaction.recoverIncompleteTransactions(game);
        assertFalse(Files.exists(transactionDirectory));
        assertFalse(Files.exists(target));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsJunctionsInManagedTargetPaths() throws Exception {
        Path external = Files.createTempDirectory(game.getParent(), "external-mods-");
        Path mods = game.resolve("mods");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                mods.toString(), external.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        try (FileModInstallationTransaction transaction =
                     new FileModInstallationTransaction(game)) {
            Path staged = Files.writeString(transaction.temporaryDirectory().resolve("one.part"), "one");
            assertThrows(IllegalArgumentException.class,
                    () -> transaction.stageDownloadedFile(staged, mods.resolve("one.jar")));
        }
        assertFalse(Files.exists(external.resolve("one.jar")));
    }
}
