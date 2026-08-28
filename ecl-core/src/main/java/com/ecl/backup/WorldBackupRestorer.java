package com.ecl.backup;

import com.ecl.util.FileUtil;
import com.ecl.util.ZipUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Restores a backup and rolls back on failure. */
final class WorldBackupRestorer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldBackupRestorer.class);
    private final WorldBackupMetadata metadataHelper;

    WorldBackupRestorer(WorldBackupMetadata metadataHelper) {
        this.metadataHelper = metadataHelper;
    }

    void restore(BackupEntry selectedBackup, Path instanceDirectory,
                 WorldBackupService.ProgressListener listener) throws IOException {
        java.util.Objects.requireNonNull(selectedBackup, "selectedBackup");
        String profileId = metadataHelper.requireProfileId(selectedBackup.profileId());
        BackupEntry backup = metadataHelper.readEntry(selectedBackup.archivePath(), profileId);
        Path instanceRoot = java.util.Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                .toAbsolutePath().normalize();
        Path parent = instanceRoot.getParent();
        if (parent == null) {
            throw new IOException("Instance directory has no parent: " + instanceRoot);
        }
        Files.createDirectories(parent);
        Files.createDirectories(instanceRoot);

        Path workspace = Files.createTempDirectory(parent,
                ".ecl-restore-" + metadataHelper.safeProfileFileName(profileId) + "-");
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
            metadataHelper.validateExtractedBackup(backup, extracted, stagedContent);
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
                if (failure == null) {
                    failure = new IOException("Failed to roll back restored files");
                }
                failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private Path safeChild(Path root, String child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(child).normalize();
        if (!result.startsWith(normalizedRoot) || result.equals(normalizedRoot)) {
            throw new IOException("Path escapes restore root: " + child);
        }
        return result;
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
