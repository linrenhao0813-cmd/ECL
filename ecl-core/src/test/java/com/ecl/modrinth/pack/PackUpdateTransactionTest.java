package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackUpdateTransactionTest {
    @TempDir Path temp;
    private Field baseDirField;
    private File previousBaseDir;

    @BeforeEach
    void isolateMetadata() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
    }

    @AfterEach
    void restoreMetadata() throws Exception {
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void recoveryDoesNotTouchInstanceWhoseNameExtendsTheRequestedName() throws Exception {
        Path instance = temp.resolve("versions/Pack");
        Files.createDirectories(instance.resolve("config"));
        Path victim = Files.writeString(instance.resolve("config/options.txt"), "user file");
        Path foreignTransaction = writeInterruptedTransaction(temp.resolve("versions/Pack-2"));

        PackUpdateTransaction.recoverIncompleteTransactions(instance, profile("Pack"));

        assertEquals("user file", Files.readString(victim));
        assertTrue(Files.isRegularFile(foreignTransaction.resolve("journal.json")));
        assertTrue(Files.isRegularFile(foreignTransaction.resolve("backups/0.bak")));
    }

    @Test
    void recoveryAndSubsequentRetryKeepStableLockMarkersWithoutFailing() throws Exception {
        Path instance = temp.resolve("versions/Pack");
        Files.createDirectories(instance.resolve("config"));
        Path target = Files.writeString(instance.resolve("config/options.txt"), "after");
        Path transaction = writeInterruptedTransaction(instance);

        assertDoesNotThrow(() -> PackUpdateTransaction.recoverIncompleteTransactions(instance, profile("Pack")));
        assertEquals("before", Files.readString(target));
        assertFalse(Files.exists(transaction));
        assertTrue(Files.isRegularFile(transaction.resolveSibling(transaction.getFileName() + ".lock")));
        assertDoesNotThrow(() -> PackUpdateTransaction.recoverIncompleteTransactions(instance, profile("Pack")));
        try (PackUpdateTransaction next = new PackUpdateTransaction(instance, profile("Pack"))) {
            Path staged = Files.writeString(next.stagingDirectory().resolve("options.txt"), "retry");
            next.stageReplacement(staged, target);
            next.commit();
        }
        assertEquals("retry", Files.readString(target));
    }

    private Path profile(String id) {
        return ECLConfig.getVersionsDir().toPath().resolve(id).resolve(id + ".json");
    }

    private Path writeInterruptedTransaction(Path instance) throws Exception {
        Path transaction = instance.getParent().resolve(".ecl-pack-transactions")
                .resolve(instance.getFileName() + "-" + UUID.randomUUID());
        Files.createDirectories(transaction.resolve("backups"));
        Files.writeString(transaction.resolve("backups/0.bak"), "before");
        Files.writeString(transaction.resolve("journal.json"), """
                {"status":"APPLYING","entries":[{"operation":"REPLACE","targetScope":"INSTANCE",
                "targetPath":"config/options.txt","stagedFile":"staged/options.txt",
                "backupFile":"backups/0.bak","targetExisted":true}]}
                """);
        return transaction;
    }
}
