package com.ecl.modrinth.transaction;

import com.ecl.util.FileLockLease;
import com.ecl.util.FileUtil;
import com.ecl.util.InstanceOperationLease;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class FileModInstallationTransaction implements ModInstallationTransaction {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileModInstallationTransaction.class);
    private static final String TRANSACTIONS_DIRECTORY = ".ecl-mod-transactions";
    private static final String JOURNAL_FILE = "journal.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path gameDirectory;
    private final Path transactionRoot;
    private final Path temporaryDirectory;
    private final Path backupDirectory;
    private final List<Stage> stages = new ArrayList<>();
    private FileLockLease directoryLock;
    private InstanceOperationLease gameOperationLock;
    private boolean committed;
    private boolean closed;

    public FileModInstallationTransaction(Path gameDirectory) throws IOException {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath().normalize();
        Path gameParent = this.gameDirectory.getParent();
        if (gameParent != null) {
            FileUtil.validateExistingAncestors(gameParent, this.gameDirectory);
        }
        Files.createDirectories(this.gameDirectory);
        this.transactionRoot = this.gameDirectory.resolve(TRANSACTIONS_DIRECTORY).normalize();
        ensureInside(this.gameDirectory, transactionRoot, "transaction root");
        FileUtil.validateExistingAncestors(this.gameDirectory, transactionRoot);
        Files.createDirectories(transactionRoot);
        this.temporaryDirectory = Files.createDirectory(
                transactionRoot.resolve(UUID.randomUUID().toString()));
        FileUtil.validateExistingAncestors(transactionRoot, temporaryDirectory);
        this.directoryLock = FileLockLease.tryAcquire(lockFile(temporaryDirectory));
        if (directoryLock == null) {
            throw new IOException("Unable to lock new mod transaction: " + temporaryDirectory);
        }
        try {
            this.gameOperationLock = InstanceOperationLease.tryAcquire(this.gameDirectory);
            if (gameOperationLock == null) {
                throw new IOException("Instance is running or busy in another launcher process: "
                        + this.gameDirectory);
            }
            this.backupDirectory = temporaryDirectory.resolve("backups");
            Files.createDirectories(backupDirectory);
        } catch (IOException | RuntimeException failure) {
            if (gameOperationLock != null) {
                try {
                    gameOperationLock.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                gameOperationLock = null;
            }
            try {
                directoryLock.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            directoryLock = null;
            throw failure;
        }
    }

    @Override
    public Path temporaryDirectory() {
        return temporaryDirectory;
    }

    @Override
    public void stageDownloadedFile(Path temporaryFile, Path finalFile) {
        addStage(null, temporaryFile, finalFile);
    }

    @Override
    public void stageReplacement(Path oldFile, Path newFile) {
        addStage(oldFile, newFile, oldFile);
    }

    @Override
    public void stageReplacement(Path oldFile, Path temporaryFile, Path finalFile) {
        addStage(oldFile, temporaryFile, finalFile);
    }

    private void addStage(Path oldFile, Path temporaryFile, Path finalFile) {
        ensureOpen();
        Path staged = normalizeRequired(temporaryFile, "temporaryFile");
        Path target = normalizeRequired(finalFile, "finalFile");
        ensureInside(temporaryDirectory, staged, "staged file");
        ensureInside(gameDirectory, target, "final file");
        try {
            FileUtil.validateExistingAncestors(temporaryDirectory, staged);
            FileUtil.validateExistingAncestors(gameDirectory, target);
        } catch (IOException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
        Path old = oldFile == null ? null : oldFile.toAbsolutePath().normalize();
        if (old != null) {
            ensureInside(gameDirectory, old, "old file");
            try {
                FileUtil.validateExistingAncestors(gameDirectory, old);
            } catch (IOException error) {
                throw new IllegalArgumentException(error.getMessage(), error);
            }
        }
        if (stages.stream().anyMatch(stage -> stage.finalFile.equals(target))) {
            throw new IllegalArgumentException("Duplicate transaction target: " + target);
        }
        stages.add(new Stage(old, staged, target));
    }

    @Override
    public synchronized void commit() throws IOException {
        ensureOpen();
        if (stages.isEmpty()) {
            committed = true;
            cleanupDirectory(temporaryDirectory);
            releaseDirectoryLock();
            return;
        }
        List<JournalEntry> entries = prepareEntries();
        writeJournal(new Journal("PREPARED", entries));
        try {
            writeJournal(new Journal("APPLYING", entries));
            for (JournalEntry entry : entries) {
                applyEntry(entry);
            }
            writeJournal(new Journal("APPLIED", entries));
            committed = true;
            cleanupDirectory(temporaryDirectory);
        } catch (IOException | RuntimeException e) {
            IOException rollbackFailure = rollbackEntries(entries);
            if (rollbackFailure != null) {
                e.addSuppressed(rollbackFailure);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
        releaseDirectoryLock();
    }

    private List<JournalEntry> prepareEntries() {
        List<JournalEntry> entries = new ArrayList<>(stages.size());
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            Path finalBackup = backupDirectory.resolve(index + "-final.bak");
            Path oldBackup = backupDirectory.resolve(index + "-old.bak");
            entries.add(new JournalEntry(
                    path(stage.stagedFile),
                    path(stage.finalFile),
                    stage.oldFile == null ? null : path(stage.oldFile),
                    path(finalBackup),
                    path(oldBackup),
                    Files.exists(stage.finalFile)));
        }
        return List.copyOf(entries);
    }

    private void applyEntry(JournalEntry entry) throws IOException {
        Path staged = resolve(entry.stagedFile());
        Path target = resolve(entry.finalFile());
        Path old = resolveNullable(entry.oldFile());
        Path finalBackup = resolve(entry.finalBackup());
        Path oldBackup = resolve(entry.oldBackup());
        if (!Files.isRegularFile(staged)) {
            throw new IOException("Missing staged transaction file: " + staged);
        }
        Files.createDirectories(target.getParent());

        if (old != null && !old.equals(target) && Files.exists(old)) {
            Files.createDirectories(oldBackup.getParent());
            move(old, oldBackup);
        }
        if (Files.exists(target)) {
            Files.createDirectories(finalBackup.getParent());
            move(target, finalBackup);
        }
        move(staged, target);
    }

    @Override
    public synchronized void rollback() {
        if (committed || closed) {
            return;
        }
        Path journalPath = temporaryDirectory.resolve(JOURNAL_FILE);
        if (Files.isRegularFile(journalPath)) {
            try {
                Journal journal = MAPPER.readValue(journalPath.toFile(), Journal.class);
                IOException failure = rollbackEntries(journal.entries());
                if (failure != null) {
                    LOGGER.warn("Failed to completely rollback mod transaction {}", temporaryDirectory, failure);
                    return;
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to read transaction journal {}", journalPath, e);
                return;
            }
        }
        try {
            cleanupDirectory(temporaryDirectory);
            releaseDirectoryLock();
        } catch (IOException e) {
            LOGGER.warn("Failed to clean transaction directory {}", temporaryDirectory, e);
        }
    }

    private IOException rollbackEntries(List<JournalEntry> entries) {
        IOException failure = null;
        List<JournalEntry> reversed = new ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        for (JournalEntry entry : reversed) {
            try {
                Path target = resolve(entry.finalFile());
                Path old = resolveNullable(entry.oldFile());
                Path finalBackup = resolve(entry.finalBackup());
                Path oldBackup = resolve(entry.oldBackup());
                if (Files.exists(finalBackup)) {
                    Files.deleteIfExists(target);
                    Files.createDirectories(target.getParent());
                    move(finalBackup, target);
                } else if (Boolean.FALSE.equals(entry.finalFileExisted())) {
                    Files.deleteIfExists(target);
                }
                if (old != null && Files.exists(oldBackup)) {
                    Files.createDirectories(old.getParent());
                    move(oldBackup, old);
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("Transaction rollback failed");
                }
                failure.addSuppressed(e);
            }
        }
        if (failure == null) {
            try {
                cleanupDirectory(temporaryDirectory);
            } catch (IOException e) {
                failure = e;
            }
        }
        return failure;
    }

    public static void recoverIncompleteTransactions(Path gameDirectory) throws IOException {
        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Path gameParent = gameRoot.getParent();
        if (gameParent != null) {
            FileUtil.validateExistingAncestors(gameParent, gameRoot);
        }
        Path transactionRoot = gameRoot.resolve(TRANSACTIONS_DIRECTORY);
        FileUtil.validateExistingAncestors(gameRoot, transactionRoot);
        if (!Files.isDirectory(transactionRoot)) {
            return;
        }
        try (InstanceOperationLease gameLock =
                     InstanceOperationLease.tryAcquire(gameRoot)) {
            if (gameLock == null) {
                return;
            }
            recoverTransactionsLocked(gameRoot, transactionRoot);
        }
    }

    private static void recoverTransactionsLocked(Path gameRoot, Path transactionRoot)
            throws IOException {
        try (var directories = Files.list(transactionRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                try (FileLockLease lock = FileLockLease.tryAcquire(lockFile(directory))) {
                    if (lock == null) {
                        LOGGER.debug("Skipping active mod transaction {}", directory);
                        continue;
                    }
                    recoverDirectory(gameRoot, directory);
                }
            }
        }
    }

    private static void recoverDirectory(Path gameRoot, Path directory) throws IOException {
        Path journalPath = directory.resolve(JOURNAL_FILE);
        if (!Files.isRegularFile(journalPath)) {
            cleanupDirectory(directory);
            return;
        }
        Journal journal = MAPPER.readValue(journalPath.toFile(), Journal.class);
        if ("APPLIED".equals(journal.status())) {
            cleanupDirectory(directory);
            return;
        }
        IOException failure = rollbackJournal(gameRoot, journal.entries());
        if (failure != null) {
            throw failure;
        }
        cleanupDirectory(directory);
    }

    private static IOException rollbackJournal(Path gameRoot, List<JournalEntry> entries) {
        IOException failure = null;
        List<JournalEntry> reversed = new ArrayList<>(entries == null ? List.of() : entries);
        java.util.Collections.reverse(reversed);
        for (JournalEntry entry : reversed) {
            try {
                Path target = safeResolve(gameRoot, entry.finalFile());
                Path old = entry.oldFile() == null ? null : safeResolve(gameRoot, entry.oldFile());
                Path finalBackup = safeResolve(gameRoot, entry.finalBackup());
                Path oldBackup = safeResolve(gameRoot, entry.oldBackup());
                if (Files.exists(finalBackup)) {
                    Files.deleteIfExists(target);
                    Files.createDirectories(target.getParent());
                    move(finalBackup, target);
                } else if (Boolean.FALSE.equals(entry.finalFileExisted())) {
                    Files.deleteIfExists(target);
                }
                if (old != null && Files.exists(oldBackup)) {
                    Files.createDirectories(old.getParent());
                    move(oldBackup, old);
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("Failed to recover incomplete mod transaction");
                }
                failure.addSuppressed(e);
            }
        }
        return failure;
    }

    private void writeJournal(Journal journal) throws IOException {
        Path target = temporaryDirectory.resolve(JOURNAL_FILE);
        Path temp = Files.createTempFile(temporaryDirectory, "journal-", ".tmp");
        try {
            byte[] bytes = MAPPER.writeValueAsString(journal).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String path(Path value) {
        return gameDirectory.relativize(value.toAbsolutePath().normalize()).toString();
    }

    private Path resolve(String relative) throws IOException {
        return safeResolve(gameDirectory, relative);
    }

    private Path resolveNullable(String relative) throws IOException {
        return relative == null ? null : resolve(relative);
    }

    private static Path safeResolve(Path gameRoot, String relative) throws IOException {
        Path result = gameRoot.resolve(relative).normalize();
        ensureInside(gameRoot, result, "journal path");
        FileUtil.validateExistingAncestors(gameRoot, result);
        return result;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path lockFile(Path directory) {
        return directory.resolveSibling(directory.getFileName() + ".lock");
    }

    private void releaseDirectoryLock() throws IOException {
        IOException failure = null;
        if (gameOperationLock != null) {
            try {
                gameOperationLock.close();
            } catch (IOException error) {
                failure = error;
            } finally {
                gameOperationLock = null;
            }
        }
        if (directoryLock != null) {
            try {
                directoryLock.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally {
                directoryLock = null;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Path normalizeRequired(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }

    private static void ensureInside(Path root, Path target, String label) {
        if (!target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(label + " escapes transaction root: " + target);
        }
    }

    private void ensureOpen() {
        if (closed || committed) {
            throw new IllegalStateException("Transaction is already closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!committed) {
            rollback();
        }
        try {
            releaseDirectoryLock();
        } catch (IOException error) {
            LOGGER.warn("Failed to release mod transaction lock {}", temporaryDirectory, error);
        }
        closed = true;
    }

    private record Stage(Path oldFile, Path stagedFile, Path finalFile) {
    }

    public record Journal(String status, List<JournalEntry> entries) {
        public Journal {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record JournalEntry(
            String stagedFile,
            String finalFile,
            String oldFile,
            String finalBackup,
            String oldBackup,
            Boolean finalFileExisted
    ) {
    }
}
