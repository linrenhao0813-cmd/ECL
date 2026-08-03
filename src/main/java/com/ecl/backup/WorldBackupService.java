package com.ecl.backup;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.GsonProvider;
import com.ecl.util.TextUtil;
import com.ecl.util.ZipUtil;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates, lists, restores and prunes local per-instance backups. */
public final class WorldBackupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldBackupService.class);
    private static final int METADATA_VERSION = 1;
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes, String currentEntry);
    }

    private final Path backupRoot;
    private final Clock clock;

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
    }

    public Path backupDirectory(String profileId) {
        String directoryName = safeProfileFileName(profileId);
        Path result = backupRoot.resolve(directoryName).normalize();
        if (!result.startsWith(backupRoot) || result.equals(backupRoot)) {
            throw new IllegalArgumentException("Invalid profile id: " + profileId);
        }
        return result;
    }

    public synchronized BackupEntry createBackup(String profileId, String sourceVersion,
                                                 Path instanceDirectory,
                                                 Set<BackupEntry.Content> requestedContent,
                                                 ProgressListener listener) throws IOException {
        String normalizedProfileId = requireProfileId(profileId);
        Path instanceRoot = Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                .toAbsolutePath().normalize();
        EnumSet<BackupEntry.Content> included = EnumSet.noneOf(BackupEntry.Content.class);
        if (requestedContent != null) included.addAll(requestedContent);
        included.add(BackupEntry.Content.SAVES);

        Path directory = backupDirectory(normalizedProfileId);
        Files.createDirectories(directory);
        Instant createdAt = clock.instant();
        String prefix = "backup-" + safeProfileFileName(normalizedProfileId) + "-"
                + FILE_TIME_FORMAT.withZone(clock.getZone()).format(createdAt);
        Path archive = uniqueArchivePath(directory, prefix);
        Path metadata = metadataPath(archive);
        Path temporaryArchive = archive.resolveSibling(archive.getFileName() + ".part");
        Path temporaryMetadata = metadata.resolveSibling(metadata.getFileName() + ".part");

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
            BackupMetadata backupMetadata = new BackupMetadata(
                    METADATA_VERSION,
                    normalizedProfileId,
                    sourceVersion == null ? "" : sourceVersion,
                    createdAt.toString(),
                    included.stream().map(BackupEntry.Content::directoryName).toList(),
                    uncompressedSize,
                    fileEntries);
            writeMetadata(temporaryMetadata, backupMetadata);
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
        String normalizedProfileId = requireProfileId(profileId);
        Path directory = backupDirectory(normalizedProfileId);
        if (!Files.isDirectory(directory)) return List.of();
        List<BackupEntry> result = new ArrayList<>();
        try (DirectoryStream<Path> archives = Files.newDirectoryStream(directory, "*.zip")) {
            for (Path archive : archives) {
                try {
                    result.add(readEntry(archive, normalizedProfileId));
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

    /**
     * Restores every content root declared by the backup. The ZIP is first validated in a staging
     * directory, then existing roots are moved into a rollback directory before replacements are
     * applied. If any swap fails, all already-touched roots are restored.
     */
    public synchronized void restore(BackupEntry selectedBackup, Path instanceDirectory,
                                     ProgressListener listener) throws IOException {
        Objects.requireNonNull(selectedBackup, "selectedBackup");
        String profileId = requireProfileId(selectedBackup.profileId());
        BackupEntry backup = readEntry(selectedBackup.archivePath(), profileId);
        Path instanceRoot = Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                .toAbsolutePath().normalize();
        Path parent = instanceRoot.getParent();
        if (parent == null) throw new IOException("Instance directory has no parent: " + instanceRoot);
        Files.createDirectories(parent);
        Files.createDirectories(instanceRoot);

        Path workspace = Files.createTempDirectory(parent,
                ".ecl-restore-" + safeProfileFileName(profileId) + "-");
        Path stagedContent = workspace.resolve("content");
        Path rollback = workspace.resolve("rollback");
        List<RestoreState> states = new ArrayList<>();
        boolean keepWorkspace = false;
        try {
            ZipUtil.ProgressListener extractionProgress = listener == null ? null
                    : (completed, ignoredTotal, entry) -> listener.onProgress(
                            completed, backup.uncompressedSize(), entry);
            List<ZipUtil.ArchivedFile> extracted = ZipUtil.extractSafely(
                    backup.archivePath(), stagedContent, extractionProgress);
            validateExtractedBackup(backup, extracted, stagedContent);
            Files.createDirectories(rollback);

            for (BackupEntry.Content content : BackupEntry.Content.values()) {
                if (!backup.includedContent().contains(content)) continue;
                Path target = safeChild(instanceRoot, content.directoryName());
                Path incoming = safeChild(stagedContent, content.directoryName());
                Path original = safeChild(rollback, content.directoryName());
                if (!Files.isDirectory(incoming)) {
                    throw new IOException("Backup is missing directory: " + content.directoryName());
                }
                RestoreState state = new RestoreState(target, original);
                states.add(state);
                if (Files.exists(target)) {
                    Files.move(target, original);
                    state.originalMoved = true;
                }
                Files.move(incoming, target);
                state.replacementMoved = true;
            }
        } catch (IOException | RuntimeException restoreError) {
            IOException rollbackError = rollback(states);
            if (rollbackError != null) {
                restoreError.addSuppressed(rollbackError);
                keepWorkspace = true;
            }
            throw restoreError;
        } finally {
            if (!keepWorkspace) {
                try {
                    FileUtil.deleteDirectory(workspace);
                } catch (IOException cleanupError) {
                    LOGGER.warn("Cannot remove restore workspace {}", workspace, cleanupError);
                }
            } else {
                LOGGER.error("Restore rollback data was retained at {}", workspace);
            }
        }
    }

    public synchronized void deleteBackup(BackupEntry backup) throws IOException {
        Objects.requireNonNull(backup, "backup");
        Path archive = validateManagedArchive(backup.archivePath(), backup.profileId());
        Path metadata = metadataPath(archive);
        Files.deleteIfExists(archive);
        Files.deleteIfExists(metadata);
    }

    public synchronized List<BackupEntry> prune(String profileId, int keepCount) throws IOException {
        if (keepCount < 0) throw new IllegalArgumentException("keepCount must not be negative");
        List<BackupEntry> backups = listBackups(profileId);
        if (backups.size() <= keepCount) return List.of();
        List<BackupEntry> removed = new ArrayList<>();
        for (int index = keepCount; index < backups.size(); index++) {
            BackupEntry backup = backups.get(index);
            deleteBackup(backup);
            removed.add(backup);
        }
        return List.copyOf(removed);
    }

    private BackupEntry readEntry(Path archivePath, String expectedProfileId) throws IOException {
        Path archive = validateManagedArchive(archivePath, expectedProfileId);
        Path metadataPath = metadataPath(archive);
        if (!Files.isRegularFile(metadataPath)) {
            throw new IOException("Backup metadata is missing: " + metadataPath.getFileName());
        }
        if (Files.isSymbolicLink(metadataPath)) {
            throw new IOException("Backup metadata cannot be a symbolic link: "
                    + metadataPath.getFileName());
        }
        BackupMetadata metadata;
        try (Reader reader = Files.newBufferedReader(metadataPath)) {
            metadata = GsonProvider.compact().fromJson(reader, BackupMetadata.class);
        } catch (JsonParseException error) {
            throw new IOException("Backup metadata is invalid: " + metadataPath.getFileName(), error);
        }
        if (metadata == null || metadata.schemaVersion() != METADATA_VERSION
                || !expectedProfileId.equals(metadata.profileId())) {
            throw new IOException("Backup metadata does not match profile " + expectedProfileId);
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(metadata.createdAt());
        } catch (RuntimeException error) {
            throw new IOException("Backup creation time is invalid", error);
        }
        EnumSet<BackupEntry.Content> included = EnumSet.noneOf(BackupEntry.Content.class);
        try {
            if (metadata.includedContent() != null) {
                for (String value : metadata.includedContent()) {
                    included.add(BackupEntry.Content.fromDirectoryName(value));
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Backup contains an unsupported content root", error);
        }
        if (!included.contains(BackupEntry.Content.SAVES)) {
            throw new IOException("Backup metadata does not include saves");
        }
        List<BackupEntry.FileEntry> files = metadata.files() == null
                ? List.of() : List.copyOf(metadata.files());
        long computedSize = files.stream().mapToLong(BackupEntry.FileEntry::size).sum();
        if (computedSize != metadata.uncompressedSize()) {
            throw new IOException("Backup metadata size is inconsistent");
        }
        return new BackupEntry(archive, metadataPath, metadata.profileId(),
                metadata.sourceVersion(), createdAt, included, Files.size(archive),
                metadata.uncompressedSize(), files);
    }

    private void validateExtractedBackup(BackupEntry backup, List<ZipUtil.ArchivedFile> extracted,
                                         Path stagedContent) throws IOException {
        Map<String, Long> expectedFiles = new HashMap<>();
        for (BackupEntry.FileEntry file : backup.files()) {
            if (expectedFiles.put(file.path(), file.size()) != null) {
                throw new IOException("Backup metadata contains duplicate file: " + file.path());
            }
        }
        Map<String, Long> actualFiles = new HashMap<>();
        for (ZipUtil.ArchivedFile file : extracted) {
            String rootName = firstArchiveSegment(file.path());
            BackupEntry.Content content;
            try {
                content = BackupEntry.Content.fromDirectoryName(rootName);
            } catch (IllegalArgumentException error) {
                throw new IOException("Backup contains unsupported path: " + file.path(), error);
            }
            if (!backup.includedContent().contains(content)) {
                throw new IOException("Backup content does not match metadata: " + file.path());
            }
            if (actualFiles.put(file.path(), file.size()) != null) {
                throw new IOException("Backup contains duplicate file: " + file.path());
            }
        }
        if (!expectedFiles.equals(actualFiles)) {
            throw new IOException("Backup file list or sizes do not match metadata");
        }
        for (BackupEntry.Content content : backup.includedContent()) {
            Path root = safeChild(stagedContent, content.directoryName());
            if (!Files.isDirectory(root)) {
                throw new IOException("Backup is missing content root: " + content.directoryName());
            }
        }
    }

    private IOException rollback(List<RestoreState> states) {
        IOException failure = null;
        for (int index = states.size() - 1; index >= 0; index--) {
            RestoreState state = states.get(index);
            try {
                if (state.replacementMoved && Files.exists(state.target)) {
                    FileUtil.deleteDirectory(state.target);
                }
                if (state.originalMoved && Files.exists(state.original)) {
                    Files.move(state.original, state.target);
                }
            } catch (IOException error) {
                if (failure == null) failure = new IOException("Failed to roll back restored files");
                failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private Path validateManagedArchive(Path archivePath, String profileId) throws IOException {
        Path directory = backupDirectory(requireProfileId(profileId));
        Path archive = Objects.requireNonNull(archivePath, "archivePath")
                .toAbsolutePath().normalize();
        if (!archive.startsWith(directory) || !archive.getParent().equals(directory)
                || !archive.getFileName().toString().endsWith(".zip")) {
            throw new IOException("Backup archive is outside the managed directory: " + archive);
        }
        if (!Files.isRegularFile(archive) || Files.isSymbolicLink(archive)) {
            throw new IOException("Backup archive does not exist: " + archive);
        }
        return archive;
    }

    private Path uniqueArchivePath(Path directory, String prefix) {
        Path candidate = directory.resolve(prefix + ".zip");
        int suffix = 2;
        while (Files.exists(candidate) || Files.exists(metadataPath(candidate))) {
            candidate = directory.resolve(prefix + "-" + suffix++ + ".zip");
        }
        return candidate;
    }

    private Path metadataPath(Path archive) {
        String name = archive.getFileName().toString();
        String baseName = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        return archive.resolveSibling(baseName + ".json");
    }

    private void writeMetadata(Path path, BackupMetadata metadata) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            GsonProvider.pretty().toJson(metadata, writer);
        }
    }

    private void moveCompletedFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private Path safeChild(Path root, String child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(child).normalize();
        if (!result.startsWith(normalizedRoot) || result.equals(normalizedRoot)) {
            throw new IOException("Path escapes restore root: " + child);
        }
        return result;
    }

    private String firstArchiveSegment(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        int slash = normalized.indexOf('/');
        String root = slash < 0 ? normalized : normalized.substring(0, slash);
        if (root.isBlank()) throw new IOException("Backup entry has no content root: " + path);
        return root;
    }

    private String requireProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        String trimmed = profileId.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid profile id: " + profileId);
        }
        return trimmed;
    }

    private String safeProfileFileName(String profileId) {
        String normalized = requireProfileId(profileId);
        String safe = TextUtil.replaceInvalidFilenameChars(normalized);
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new IllegalArgumentException("Invalid profile id: " + profileId);
        }
        return safe;
    }

    private ZipUtil.ProgressListener adapt(ProgressListener listener) {
        if (listener == null) return null;
        return listener::onProgress;
    }

    private record BackupMetadata(int schemaVersion, String profileId, String sourceVersion,
                                  String createdAt, List<String> includedContent,
                                  long uncompressedSize, List<BackupEntry.FileEntry> files) {
    }

    private static final class RestoreState {
        private final Path target;
        private final Path original;
        private boolean originalMoved;
        private boolean replacementMoved;

        private RestoreState(Path target, Path original) {
            this.target = target;
            this.original = original;
        }
    }
}
