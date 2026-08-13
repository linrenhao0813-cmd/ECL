package com.ecl.modrinth.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModInstallationTransactionTest {
    @TempDir Path game;

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
    void recoveryRemovesAbandonedTransactionWithoutJournal() throws Exception {
        FileModInstallationTransaction abandoned = new FileModInstallationTransaction(game);
        Path directory = abandoned.temporaryDirectory();
        Files.writeString(directory.resolve("orphan.part"), "orphan");
        FileModInstallationTransaction.recoverIncompleteTransactions(game);
        assertFalse(Files.exists(directory));
        abandoned.close();
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
}
