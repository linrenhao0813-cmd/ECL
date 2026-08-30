package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.util.FileLockLease;
import com.ecl.util.InstanceOperationLease;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Journaled transaction for an instance tree and its external ECL profile JSON.
 *
 * <p>It uses the same PREPARED/APPLYING/APPLIED journal protocol and reverse-order rollback as
 * {@code FileModInstallationTransaction}, while adding deletion entries and explicitly scoped
 * instance, profile, versions, and libraries roots. Journal paths can therefore never address
 * arbitrary files.</p>
 */
public final class PackUpdateTransaction implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackUpdateTransaction.class);
    private static final String TRANSACTIONS_DIRECTORY = ".ecl-pack-transactions";
    private static final String JOURNAL_FILE = "journal.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path instanceRoot;
    private final Path profileFile;
    private final Path versionsRoot;
    private final Path librariesRoot;
    private final Path transactionRoot;
    private final Path transactionDirectory;
    private final Path stagingDirectory;
    private final Path backupDirectory;
    private final List<Stage> stages = new ArrayList<>();
    private final int failAfterAppliedEntries;
    private FileLockLease operationLock;
    private InstanceOperationLease gameOperationLock;
    private boolean committed;
    private boolean closed;

    public PackUpdateTransaction(Path instanceRoot, Path profileFile) throws IOException {
        this(instanceRoot, profileFile, Integer.MAX_VALUE);
    }

    PackUpdateTransaction(Path instanceRoot, Path profileFile, int failAfterAppliedEntries)
            throws IOException {
        this.instanceRoot = Objects.requireNonNull(instanceRoot, "instanceRoot")
                .toAbsolutePath().normalize();
        this.profileFile = Objects.requireNonNull(profileFile, "profileFile")
                .toAbsolutePath().normalize();
        this.versionsRoot = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        this.librariesRoot = ECLConfig.getLibrariesDir().toPath().toAbsolutePath().normalize();
        if (this.instanceRoot.getParent() == null || this.profileFile.getParent() == null) {
            throw new IOException("Pack transaction roots must have parent directories");
        }
        if (!this.profileFile.startsWith(this.versionsRoot)) {
            throw new IOException("Pack profile metadata must be inside the versions directory");
        }
        MrpackPathPolicy.validateExistingAncestors(this.instanceRoot.getParent(), this.instanceRoot);
        MrpackPathPolicy.validateExistingAncestors(this.versionsRoot, this.profileFile);
        this.failAfterAppliedEntries = failAfterAppliedEntries;
        Files.createDirectories(this.instanceRoot);
        Files.createDirectories(this.profileFile.getParent());
        this.transactionRoot = this.instanceRoot.getParent().resolve(TRANSACTIONS_DIRECTORY)
                .toAbsolutePath().normalize();
        MrpackPathPolicy.validateExistingAncestors(this.instanceRoot.getParent(), this.transactionRoot);
        Files.createDirectories(transactionRoot);
        this.operationLock = FileLockLease.tryAcquire(operationLockFile(this.instanceRoot));
        if (operationLock == null) {
            throw new IOException("Unable to lock pack instance operation: " + instanceRoot);
        }
        try {
            this.gameOperationLock = InstanceOperationLease.tryAcquire(this.instanceRoot);
            if (gameOperationLock == null) {
                throw new IOException("Unable to lock running game directory: " + instanceRoot);
            }
            this.transactionDirectory = Files.createDirectory(
                    transactionRoot.resolve(this.instanceRoot.getFileName() + "-" + UUID.randomUUID()));
            this.stagingDirectory = Files.createDirectory(transactionDirectory.resolve("staged"));
            this.backupDirectory = Files.createDirectory(transactionDirectory.resolve("backups"));
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
                operationLock.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            operationLock = null;
            throw failure;
        }
    }

    public Path stagingDirectory() {
        return stagingDirectory;
    }

    public void stageReplacement(Path stagedFile, Path target) {
        addStage(Operation.REPLACE, stagedFile, target);
    }

    /** Stage generated loader files under the shared versions/libraries roots. */
    public void stageExternalDirectory(Path stagedRoot, Path targetRoot) throws IOException {
        ensureOpen();
        Path sourceRoot = Objects.requireNonNull(stagedRoot, "stagedRoot")
                .toAbsolutePath().normalize();
        Path normalizedTargetRoot = Objects.requireNonNull(targetRoot, "targetRoot")
                .toAbsolutePath().normalize();
        externalScope(normalizedTargetRoot);
        if (!Files.exists(sourceRoot)) {
            return;
        }
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                if (Files.isSymbolicLink(source)) {
                    throw new IOException("Loader staging contains a symbolic link: " + source);
                }
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                Path relative = sourceRoot.relativize(source).normalize();
                addStage(Operation.REPLACE, source, normalizedTargetRoot.resolve(relative).normalize());
            }
        }
    }

    public void stageDeletion(Path target) {
        addStage(Operation.DELETE, null, target);
    }

    private void addStage(Operation operation, Path stagedFile, Path target) {
        ensureOpen();
        Path normalizedTarget = normalizeTarget(target);
        Path normalizedStaged = stagedFile == null ? null : stagedFile.toAbsolutePath().normalize();
        if (normalizedStaged != null && !normalizedStaged.startsWith(stagingDirectory)) {
            throw new IllegalArgumentException("Staged file escapes transaction directory: " + stagedFile);
        }
        if (stages.stream().anyMatch(stage -> stage.target().equals(normalizedTarget))) {
            throw new IllegalArgumentException("Duplicate pack transaction target: " + target);
        }
        stages.add(new Stage(operation, normalizedStaged, normalizedTarget));
    }

    public synchronized void commit() throws IOException {
        ensureOpen();
        List<JournalEntry> entries = prepareEntries();
        writeJournal(new Journal("PREPARED", entries));
        try {
            writeJournal(new Journal("APPLYING", entries));
            int applied = 0;
            for (JournalEntry entry : entries) {
                applyEntry(entry);
                applied++;
                if (applied == failAfterAppliedEntries) {
                    throw new IOException("Injected pack transaction failure");
                }
            }
            writeJournal(new Journal("APPLIED", entries));
            committed = true;
            cleanupDirectory(transactionDirectory);
        } catch (IOException | RuntimeException error) {
            IOException rollbackFailure = rollbackEntries(entries);
            if (rollbackFailure != null) {
                error.addSuppressed(rollbackFailure);
            }
            throw error;
        }
        releaseDirectoryLock();
        cleanupTransactionRoot();
    }

    private List<JournalEntry> prepareEntries() {
        List<JournalEntry> entries = new ArrayList<>(stages.size());
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            Target target = target(stage.target());
            entries.add(new JournalEntry(
                    stage.operation().name(),
                    target.scope().name(),
                    target.relative(),
                    stage.stagedFile() == null ? null : transactionDirectory.relativize(
                            stage.stagedFile()).toString(),
                    transactionDirectory.relativize(backupDirectory.resolve(index + ".bak")).toString(),
                    Files.exists(stage.target())));
        }
        return List.copyOf(entries);
    }

    private void applyEntry(JournalEntry entry) throws IOException {
        Path target = resolveTarget(entry);
        Path backup = resolveTransactionPath(entry.backupFile());
        if (Boolean.TRUE.equals(entry.targetExisted()) && Files.exists(target)) {
            Files.createDirectories(backup.getParent());
            move(target, backup);
        }
        if (Operation.REPLACE.name().equals(entry.operation())) {
            Path staged = resolveTransactionPath(entry.stagedFile());
            if (!Files.isRegularFile(staged)) {
                throw new IOException("Missing staged pack transaction file: " + staged);
            }
            Files.createDirectories(target.getParent());
            move(staged, target);
        } else if (!Operation.DELETE.name().equals(entry.operation())) {
            throw new IOException("Unknown pack transaction operation: " + entry.operation());
        }
    }

    public synchronized void rollback() {
        if (committed || closed) {
            return;
        }
        Path journalPath = transactionDirectory.resolve(JOURNAL_FILE);
        if (Files.isRegularFile(journalPath)) {
            try {
                Journal journal = MAPPER.readValue(journalPath.toFile(), Journal.class);
                IOException failure = rollbackEntries(journal.entries());
                if (failure != null) {
                    LOGGER.warn("Failed to completely rollback pack transaction {}",
                            transactionDirectory, failure);
                    return;
                }
            } catch (IOException error) {
                LOGGER.warn("Failed to read pack transaction journal {}", journalPath, error);
                return;
            }
        } else {
            LOGGER.warn("Preserving pack transaction without a journal: {}", transactionDirectory);
            try {
                releaseDirectoryLock();
            } catch (IOException error) {
                LOGGER.warn("Failed to release pack transaction lock {}", transactionDirectory, error);
            }
            return;
        }
        try {
            cleanupDirectory(transactionDirectory);
            releaseDirectoryLock();
            cleanupTransactionRoot();
        } catch (IOException error) {
            LOGGER.warn("Failed to clean pack transaction directory {}", transactionDirectory, error);
        }
    }

    private IOException rollbackEntries(List<JournalEntry> entries) {
        IOException failure = null;
        List<JournalEntry> reversed = new ArrayList<>(entries == null ? List.of() : entries);
        java.util.Collections.reverse(reversed);
        for (JournalEntry entry : reversed) {
            try {
                Path target = resolveTarget(entry);
                Path backup = resolveTransactionPath(entry.backupFile());
                if (Files.exists(backup)) {
                    Files.deleteIfExists(target);
                    Files.createDirectories(target.getParent());
                    move(backup, target);
                } else if (Boolean.FALSE.equals(entry.targetExisted())) {
                    Files.deleteIfExists(target);
                }
            } catch (IOException | RuntimeException error) {
                if (failure == null) {
                    failure = new IOException("Pack transaction rollback failed");
                }
                failure.addSuppressed(error);
            }
        }
        if (failure == null) {
            try {
                cleanupDirectory(transactionDirectory);
                cleanupTransactionRoot();
            } catch (IOException error) {
                failure = error;
            }
        }
        return failure;
    }

    public static void recoverIncompleteTransactions(Path instanceRoot, Path profileFile)
            throws IOException {
        Path normalizedInstance = Objects.requireNonNull(instanceRoot, "instanceRoot")
                .toAbsolutePath().normalize();
        Path normalizedProfile = Objects.requireNonNull(profileFile, "profileFile")
                .toAbsolutePath().normalize();
        Path root = normalizedInstance.getParent() == null ? null
                : normalizedInstance.getParent().resolve(TRANSACTIONS_DIRECTORY).normalize();
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (FileLockLease operationLock = FileLockLease.tryAcquire(
                operationLockFile(normalizedInstance))) {
            if (operationLock == null) {
                return;
            }
            try (InstanceOperationLease gameLock =
                         InstanceOperationLease.tryAcquire(normalizedInstance)) {
                if (gameLock == null) {
                    return;
                }
                recoverTransactionDirectories(normalizedInstance, normalizedProfile, root);
            }
        }
        try (var remaining = Files.list(root)) {
            if (remaining.filter(path -> !path.getFileName().toString().endsWith(".lock"))
                    .findAny().isEmpty()) {
                Files.deleteIfExists(root);
            }
        }
    }

    private static void recoverTransactionDirectories(Path normalizedInstance, Path profileFile,
                                                      Path root) throws IOException {
        String prefix = normalizedInstance.getFileName() + "-";
        try (var directories = Files.list(root)) {
            for (Path directory : directories
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList()) {
                recoverDirectory(normalizedInstance, profileFile, directory);
            }
        }
    }

    private static void recoverDirectory(Path instanceRoot, Path profileFile, Path directory)
            throws IOException {
        try (FileLockLease lock = FileLockLease.tryAcquire(directoryLockFile(directory))) {
            if (lock == null) {
                LOGGER.debug("Skipping active pack transaction {}", directory);
                return;
            }
            recoverLockedDirectory(instanceRoot, profileFile, directory);
        }
    }

    private static void recoverLockedDirectory(Path instanceRoot, Path profileFile, Path directory)
            throws IOException {
        Path journalPath = directory.resolve(JOURNAL_FILE);
        if (!Files.isRegularFile(journalPath)) {
            // A crash can happen after a backup move and before the first journal rename. Keep
            // the directory for inspection/recovery instead of deleting possible rollback data.
            LOGGER.warn("Preserving pack transaction without a journal: {}", directory);
            return;
        }
        Journal journal = MAPPER.readValue(journalPath.toFile(), Journal.class);
        if ("APPLIED".equals(journal.status())) {
            cleanupDirectory(directory);
            return;
        }
        PackUpdateTransaction recovery = new PackUpdateTransaction(
                instanceRoot, profileFile, Integer.MAX_VALUE, directory);
        IOException failure = recovery.rollbackEntries(journal.entries());
        recovery.closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private PackUpdateTransaction(Path instanceRoot, Path profileFile, int failAfterAppliedEntries,
                                  Path existingDirectory) {
        this.instanceRoot = instanceRoot;
        this.profileFile = profileFile;
        this.versionsRoot = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        this.librariesRoot = ECLConfig.getLibrariesDir().toPath().toAbsolutePath().normalize();
        this.failAfterAppliedEntries = failAfterAppliedEntries;
        this.transactionRoot = existingDirectory.getParent();
        this.transactionDirectory = existingDirectory;
        this.stagingDirectory = existingDirectory.resolve("staged");
        this.backupDirectory = existingDirectory.resolve("backups");
    }

    private void writeJournal(Journal journal) throws IOException {
        Path target = transactionDirectory.resolve(JOURNAL_FILE);
        Path temporary = Files.createTempFile(transactionDirectory, "journal-", ".tmp");
        try {
            byte[] bytes = MAPPER.writeValueAsString(journal).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            move(temporary, target);
            forceDirectory(transactionDirectory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path normalizeTarget(Path target) {
        Path result = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!result.startsWith(instanceRoot) && !result.equals(profileFile)
                && !result.startsWith(versionsRoot) && !result.startsWith(librariesRoot)) {
            throw new IllegalArgumentException("Pack transaction target is outside its roots: " + target);
        }
        return result;
    }

    private Target target(Path path) {
        if (path.equals(profileFile)) {
            return new Target(Scope.PROFILE, "");
        }
        if (path.startsWith(versionsRoot)) {
            return new Target(Scope.VERSIONS, portable(versionsRoot.relativize(path)));
        }
        if (path.startsWith(librariesRoot)) {
            return new Target(Scope.LIBRARIES, portable(librariesRoot.relativize(path)));
        }
        return new Target(Scope.INSTANCE, portable(instanceRoot.relativize(path)));
    }

    private Path resolveTarget(JournalEntry entry) throws IOException {
        Scope scope;
        try {
            scope = Scope.valueOf(entry.targetScope());
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack transaction target scope", error);
        }
        if (scope == Scope.PROFILE) {
            if (entry.targetPath() != null && !entry.targetPath().isBlank()) {
                throw new IOException("Invalid profile target in pack transaction journal");
            }
            MrpackPathPolicy.validateExistingAncestors(versionsRoot, profileFile);
            return profileFile;
        }
        if (scope == Scope.VERSIONS) {
            return PackManifest.resolve(versionsRoot, entry.targetPath());
        }
        if (scope == Scope.LIBRARIES) {
            return PackManifest.resolve(librariesRoot, entry.targetPath());
        }
        return PackManifest.resolve(instanceRoot, entry.targetPath());
    }

    private Scope externalScope(Path root) throws IOException {
        if (root.equals(versionsRoot)) {
            return Scope.VERSIONS;
        }
        if (root.equals(librariesRoot)) {
            return Scope.LIBRARIES;
        }
        throw new IOException("Unsupported external transaction root: " + root);
    }

    private Path resolveTransactionPath(String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("Missing pack transaction path");
        }
        Path result = transactionDirectory.resolve(relative).normalize();
        if (!result.startsWith(transactionDirectory)) {
            throw new IOException("Pack transaction journal path escapes its directory");
        }
        return result;
    }

    private void cleanupTransactionRoot() throws IOException {
        if (!Files.isDirectory(transactionRoot)) {
            return;
        }
        try (var remaining = Files.list(transactionRoot)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(transactionRoot);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        forceFile(source);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException atomicError) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException moveError) {
                if (sameFileStore(source, target)) {
                    throw moveError;
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                forceFile(target);
                Files.delete(source);
            }
        }
        forceDirectory(target.toAbsolutePath().normalize().getParent());
    }

    private static boolean sameFileStore(Path source, Path target) throws IOException {
        Path sourceParent = Objects.requireNonNull(source.toAbsolutePath().normalize().getParent(),
                "source parent");
        Path targetParent = Objects.requireNonNull(target.toAbsolutePath().normalize().getParent(),
                "target parent");
        return Files.getFileStore(sourceParent).equals(Files.getFileStore(targetParent));
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

    private static Path operationLockFile(Path instanceRoot) {
        return instanceRoot.resolveSibling(instanceRoot.getFileName() + ".pack.lock");
    }

    private static Path directoryLockFile(Path directory) {
        return directory.resolveSibling(directory.getFileName() + ".lock");
    }

    private static void forceFile(Path file) throws IOException {
        if (file != null && Files.isRegularFile(file)) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (java.nio.file.AccessDeniedException | UnsupportedOperationException unsupported) {
            // The Windows provider does not allow opening a directory as a FileChannel. File
            // contents and the journal are still forced; directory metadata is best effort there.
            LOGGER.debug("Directory fsync is unavailable for {}", directory, unsupported);
        }
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
        if (operationLock != null) {
            try {
                operationLock.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally {
                operationLock = null;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void ensureOpen() {
        if (closed || committed) {
            throw new IllegalStateException("Pack transaction is already closed");
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
            LOGGER.warn("Failed to release pack transaction lock {}", transactionDirectory, error);
        }
        closed = true;
    }

    private enum Operation { REPLACE, DELETE }

    private enum Scope { INSTANCE, PROFILE, VERSIONS, LIBRARIES }

    private record Stage(Operation operation, Path stagedFile, Path target) {
    }

    private record Target(Scope scope, String relative) {
    }

    public record Journal(String status, List<JournalEntry> entries) {
        public Journal {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record JournalEntry(
            String operation,
            String targetScope,
            String targetPath,
            String stagedFile,
            String backupFile,
            Boolean targetExisted
    ) {
    }
}
