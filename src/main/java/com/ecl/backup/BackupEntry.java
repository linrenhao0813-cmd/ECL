package com.ecl.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Metadata describing one restorable instance backup. */
public final class BackupEntry {
    public enum Content {
        SAVES("saves", "存档"),
        MODS("mods", "模组"),
        SHADERPACKS("shaderpacks", "光影包"),
        RESOURCEPACKS("resourcepacks", "材质包"),
        CONFIG("config", "配置");

        private final String directoryName;
        private final String displayName;

        Content(String directoryName, String displayName) {
            this.directoryName = directoryName;
            this.displayName = displayName;
        }

        public String directoryName() {
            return directoryName;
        }

        public String displayName() {
            return displayName;
        }

        public static Content fromDirectoryName(String value) {
            for (Content content : values()) {
                if (content.directoryName.equals(value)) return content;
            }
            throw new IllegalArgumentException("Unknown backup content: " + value);
        }
    }

    public record FileEntry(String path, long size) {
        public FileEntry {
            Objects.requireNonNull(path, "path");
            if (size < 0) throw new IllegalArgumentException("size must not be negative");
        }
    }

    private final Path archivePath;
    private final Path metadataPath;
    private final String profileId;
    private final String sourceVersion;
    private final Instant createdAt;
    private final Set<Content> includedContent;
    private final long archiveSize;
    private final long uncompressedSize;
    private final List<FileEntry> files;

    public BackupEntry(Path archivePath, Path metadataPath, String profileId, String sourceVersion,
                       Instant createdAt, Set<Content> includedContent, long archiveSize,
                       long uncompressedSize, List<FileEntry> files) {
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath").toAbsolutePath().normalize();
        this.metadataPath = Objects.requireNonNull(metadataPath, "metadataPath").toAbsolutePath().normalize();
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.sourceVersion = sourceVersion == null ? "" : sourceVersion;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.includedContent = Set.copyOf(includedContent);
        this.archiveSize = archiveSize;
        this.uncompressedSize = uncompressedSize;
        this.files = List.copyOf(files);
    }

    public Path archivePath() { return archivePath; }
    public Path metadataPath() { return metadataPath; }
    public String profileId() { return profileId; }
    public String sourceVersion() { return sourceVersion; }
    public Instant createdAt() { return createdAt; }
    public Set<Content> includedContent() { return includedContent; }
    public long archiveSize() { return archiveSize; }
    public long uncompressedSize() { return uncompressedSize; }
    public List<FileEntry> files() { return files; }
}
