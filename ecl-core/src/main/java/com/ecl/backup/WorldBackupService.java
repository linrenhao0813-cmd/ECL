package com.ecl.backup;

import com.ecl.ECLConfig;
import com.ecl.util.FileLockLease;
import com.ecl.util.ZipUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates, lists, restores and prunes local per-instance backups. */
public final class WorldBackupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldBackupService.class);
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes, String currentEntry);
    }

    private final Path backupRoot;
    private final Clock clock;
    private final WorldBackupMetadata metadataHelper;
    private final WorldBackupRestorer restorer;

    public WorldBackupService() {
        this(ECLConfig.getBaseDir().toPath().resolve("backups"), Clock.systemDefaultZone());
    }

    public WorldBackupService(Path backupRoot) {
        this(backupRoot, Clock.systemDefaultZone());
    }

    WorldBackupService(Path backupRoot, Clock clock) {
        this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot")
                .toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metadataHelper = new WorldBackupMetadata(this.backupRoot);
        this.restorer = new WorldBackupRestorer(metadataHelper);
    }

    public Path backupDirectory(String profileId) {
        return metadataHelper.backupDirectory(profileId);
    }

    public synchronized BackupEntry createBackup(String profileId, String sourceVersion,
                                                 Path instanceDirectory,
                                                 Set<BackupEntry.Content> requestedContent,
                                                 ProgressListener listener) throws IOException {
        String normalizedProfileId = metadataHelper.requireProfileId(profileId);
        return withProfileLock(normalizedProfileId,
                () -> createBackupLocked(normalizedProfileId, sourceVersion, instanceDirectory,
                        requestedContent, listener));
    }

    private BackupEntry createBackupLocked(String normalizedProfileId, String sourceVersion,
                                           Path instanceDirectory,
                                           Set<BackupEntry.Content> requestedContent,
                                           ProgressListener listener) throws IOException {
        Path instanceRoot = Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                .toAbsolutePath().normalize();
        EnumSet<BackupEntry.Content> included = EnumSet.noneOf(BackupEntry.Content.class);
        if (requestedContent != null) included.addAll(requestedContent);
        included.add(BackupEntry.Content.SAVES);

        Path directory = metadataHelper.backupDirectory(normalizedProfileId);
        Files.createDirectories(directory);
        Instant createdAt = clock.instant();
        String prefix = "backup-" + metadataHelper.safeProfileFileName(normalizedProfileId) + "-"
                + FILE_TIME_FORMAT.withZone(clock.getZone()).format(createdAt);
        Path archive = metadataHelper.uniqueArchivePath(directory, prefix);
        Path metadata = metadataHelper.metadataPath(archive);
        String temporarySuffix = "." + java.util.UUID.randomUUID() + ".part";
        Path temporaryArchive = archive.resolveSibling(archive.getFileName() + temporarySuffix);
        Path temporaryMetadata = metadata.resolveSibling(metadata.getFileName() + temporarySuffix);

        Map<String, Path> sources = new LinkedHashMap<>();
        for (BackupEntry.Content content : BackupEntry.Content.values()) {
            if (!included.contains(content)) continue;
            Path source = instanceRoot.resolve(content.directoryName()).normalize();
            if (!source.startsWith(instanceRoot) || source.equals(instanceRoot)) {
                throw new IOException("Backup source escapes the instance directory: " + source);
            }
            sources.put(content.directoryName(), source);
        }

        try {
            List<ZipUtil.ArchivedFile> archivedFiles = ZipUtil.zipDirectories(
                    sources, temporaryArchive, adapt(listener));
            List<BackupEntry.FileEntry> fileEntries = archivedFiles.stream()
                    .map(file -> new BackupEntry.FileEntry(file.path(), file.size()))
                    .toList();
            long uncompressedSize = fileEntries.stream()
                    .mapToLong(BackupEntry.FileEntry::size).sum();
            WorldBackupMetadata.BackupMetadata backupMetadata = metadataHelper.newMetadata(
                    WorldBackupMetadata.METADATA_VERSION,
                    normalizedProfileId,
                    sourceVersion == null ? "" : sourceVersion,
                    createdAt.toString(),
                    included.stream().map(BackupEntry.Content::directoryName).toList(),
                    uncompressedSize,
                    fileEntries);
            metadataHelper.writeMetadata(temporaryMetadata, backupMetadata);
            moveCompletedFile(temporaryArchive, archive);
            try {
                moveCompletedFile(temporaryMetadata, metadata);
            } catch (IOException error) {
                Files.deleteIfExists(archive);
                throw error;
            }
            return new BackupEntry(archive, metadata, normalizedProfileId,
                    backupMetadata.sourceVersion(), createdAt, included,
                    Files.size(archive), uncompressedSize, fileEntries);
        } finally {
            Files.deleteIfExists(temporaryArchive);
            Files.deleteIfExists(temporaryMetadata);
        }
    }

    public synchronized List<BackupEntry> listBackups(String profileId) throws IOException {
        String normalizedProfileId = metadataHelper.requireProfileId(profileId);
        return withProfileLock(normalizedProfileId, () -> listBackupsLocked(normalizedProfileId));
    }

    private List<BackupEntry> listBackupsLocked(String normalizedProfileId) throws IOException {
        Path directory = metadataHelper.backupDirectory(normalizedProfileId);
        if (!Files.isDirectory(directory)) return List.of();
        List<BackupEntry> result = new ArrayList<>();
        try (DirectoryStream<Path> archives = Files.newDirectoryStream(directory, "*.zip")) {
            for (Path archive : archives) {
                try {
                    result.add(metadataHelper.readEntry(archive, normalizedProfileId));
                } catch (IOException | RuntimeException error) {
                    LOGGER.warn("Ignoring unreadable world backup {}", archive, error);
                }
            }
        }
        result.sort(Comparator.comparing(BackupEntry::createdAt).reversed()
                .thenComparing(entry -> entry.archivePath().getFileName().toString(),
                        Comparator.reverseOrder()));
        return List.copyOf(result);
    }

    public synchronized void restore(BackupEntry selectedBackup, Path instanceDirectory,
                                     ProgressListener listener) throws IOException {
        Objects.requireNonNull(selectedBackup, "backup");
        String profileId = metadataHelper.requireProfileId(selectedBackup.profileId());
        withProfileLock(profileId, () -> {
            restorer.restore(selectedBackup, instanceDirectory, listener);
            return null;
        });
    }

    public synchronized void deleteBackup(BackupEntry backup) throws IOException {
        Objects.requireNonNull(backup, "backup");
        String profileId = metadataHelper.requireProfileId(backup.profileId());
        withProfileLock(profileId, () -> {
            deleteBackupLocked(backup, profileId);
            return null;
        });
    }

    private void deleteBackupLocked(BackupEntry backup, String profileId) throws IOException {
        Path archive = metadataHelper.validateManagedArchive(backup.archivePath(), profileId);
        Path metadata = metadataHelper.metadataPath(archive);
        Files.deleteIfExists(archive);
        Files.deleteIfExists(metadata);
    }

    public synchronized List<BackupEntry> prune(String profileId, int keepCount) throws IOException {
        if (keepCount < 0) throw new IllegalArgumentException("keepCount must not be negative");
        String normalizedProfileId = metadataHelper.requireProfileId(profileId);
        return withProfileLock(normalizedProfileId,
                () -> pruneLocked(normalizedProfileId, keepCount));
    }

    private List<BackupEntry> pruneLocked(String normalizedProfileId, int keepCount)
            throws IOException {
        List<BackupEntry> backups = listBackupsLocked(normalizedProfileId);
        if (backups.size() <= keepCount) return List.of();
        List<BackupEntry> removed = new ArrayList<>();
        for (int index = keepCount; index < backups.size(); index++) {
            BackupEntry backup = backups.get(index);
            deleteBackupLocked(backup, normalizedProfileId);
            removed.add(backup);
        }
        return List.copyOf(removed);
    }

    @FunctionalInterface
    private interface ProfileOperation<T> {
        T run() throws IOException;
    }

    private <T> T withProfileLock(String profileId, ProfileOperation<T> operation)
            throws IOException {
        Path lockDirectory = backupRoot.resolve(".locks").toAbsolutePath().normalize();
        Files.createDirectories(lockDirectory);
        Path lockFile = lockDirectory.resolve(metadataHelper.safeProfileFileName(profileId) + ".lock");
        try (FileLockLease ignored = FileLockLease.tryAcquire(lockFile)) {
            if (ignored == null) {
                throw new IOException("Backup profile is busy: " + profileId);
            }
            return operation.run();
        }
    }

    private ZipUtil.ProgressListener adapt(ProgressListener listener) {
        if (listener == null) return null;
        return listener::onProgress;
    }

    private void moveCompletedFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }
}
