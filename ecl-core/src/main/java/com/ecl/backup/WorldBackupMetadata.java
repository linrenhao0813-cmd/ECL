package com.ecl.backup;

import com.ecl.util.GsonProvider;
import com.ecl.util.TextUtil;
import com.ecl.util.ZipUtil;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns backup metadata, archive naming, and managed-path validation. */
final class WorldBackupMetadata {
    static final int METADATA_VERSION = 1;

    private final Path backupRoot;

    WorldBackupMetadata(Path backupRoot) {
        this.backupRoot = backupRoot.toAbsolutePath().normalize();
    }

    Path backupDirectory(String profileId) {
        String directoryName = safeProfileFileName(profileId);
        Path result = backupRoot.resolve(directoryName).normalize();
        if (!result.startsWith(backupRoot) || result.equals(backupRoot)) {
            throw new IllegalArgumentException("Invalid profile id: " + profileId);
        }
        return result;
    }

    Path metadataPath(Path archive) {
        String name = archive.getFileName().toString();
        String baseName = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        return archive.resolveSibling(baseName + ".json");
    }

    Path uniqueArchivePath(Path directory, String prefix) {
        Path candidate = directory.resolve(prefix + ".zip");
        int suffix = 2;
        while (Files.exists(candidate) || Files.exists(metadataPath(candidate))) {
            candidate = directory.resolve(prefix + "-" + suffix++ + ".zip");
        }
        return candidate;
    }

    BackupEntry readEntry(Path archivePath, String expectedProfileId) throws IOException {
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

    void writeMetadata(Path path, BackupMetadata metadata) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Backup metadata temporary path cannot be a symbolic link: " + path);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            GsonProvider.pretty().toJson(metadata, writer);
        }
    }

    void validateExtractedBackup(BackupEntry backup, List<ZipUtil.ArchivedFile> extracted,
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

    Path validateManagedArchive(Path archivePath, String profileId) throws IOException {
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

    String requireProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        String trimmed = profileId.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid profile id: " + profileId);
        }
        return trimmed;
    }

    String safeProfileFileName(String profileId) {
        String normalized = requireProfileId(profileId);
        String safe = TextUtil.replaceInvalidFilenameChars(normalized);
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new IllegalArgumentException("Invalid profile id: " + profileId);
        }
        return safe;
    }

    BackupMetadata newMetadata(int schemaVersion, String profileId, String sourceVersion,
                               String createdAt, List<String> includedContent,
                               long uncompressedSize, List<BackupEntry.FileEntry> files) {
        return new BackupMetadata(schemaVersion, profileId, sourceVersion, createdAt,
                includedContent, uncompressedSize, files);
    }

    private String firstArchiveSegment(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        int slash = normalized.indexOf('/');
        String root = slash < 0 ? normalized : normalized.substring(0, slash);
        if (root.isBlank()) {
            throw new IOException("Backup entry has no content root: " + path);
        }
        return root;
    }

    private Path safeChild(Path root, String child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(child).normalize();
        if (!result.startsWith(normalizedRoot) || result.equals(normalizedRoot)) {
            throw new IOException("Path escapes restore root: " + child);
        }
        return result;
    }

    record BackupMetadata(int schemaVersion, String profileId, String sourceVersion,
                          String createdAt, List<String> includedContent,
                          long uncompressedSize, List<BackupEntry.FileEntry> files) {
    }
}
