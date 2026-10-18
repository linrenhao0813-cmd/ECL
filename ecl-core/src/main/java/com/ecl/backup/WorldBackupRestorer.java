package com.ecl.backup;

import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.ZipUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Restores directories with a write-ahead journal; callers hold the instance lifecycle lock. */
final class WorldBackupRestorer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldBackupRestorer.class);
    private static final String WORKSPACE = ".ecl/world-restore";
    private static final String JOURNAL = "journal.json";
    private final WorldBackupMetadata metadataHelper;

    WorldBackupRestorer(WorldBackupMetadata metadataHelper) {
        this.metadataHelper = metadataHelper;
    }

    void restore(BackupEntry selectedBackup, Path instanceDirectory,
                 WorldBackupService.ProgressListener listener) throws IOException {
        java.util.Objects.requireNonNull(selectedBackup, "selectedBackup");
        String profileId = metadataHelper.requireProfileId(selectedBackup.profileId());
        BackupEntry backup = metadataHelper.readEntry(selectedBackup.archivePath(), profileId);
        Path instanceRoot = instanceDirectory.toAbsolutePath().normalize();
        Path workspace = safeChild(instanceRoot, WORKSPACE);
        Files.createDirectories(workspace);
        Path stagedContent = workspace.resolve("content");
        Path rollback = workspace.resolve("rollback");
        boolean journalWritten = false;
        boolean committed = false;
        try {
            ZipUtil.ProgressListener extractionProgress = listener == null ? null
                    : (completed, ignoredTotal, entry) -> listener.onProgress(
                            completed, backup.uncompressedSize(), entry);
            List<ZipUtil.ArchivedFile> extracted = ZipUtil.extractSafely(
                    backup.archivePath(), stagedContent, extractionProgress);
            metadataHelper.validateExtractedBackup(backup, extracted, stagedContent);
            Files.createDirectories(rollback);
            List<RestoreState> states = new ArrayList<>();
            for (BackupEntry.Content content : BackupEntry.Content.values()) {
                if (!backup.includedContent().contains(content)) continue;
                String name = content.directoryName();
                Path incoming = safeChild(stagedContent, name);
                if (!Files.isDirectory(incoming, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Backup is missing directory: " + name);
                }
                states.add(new RestoreState(name, Files.exists(safeChild(instanceRoot, name))));
            }
            writeJournal(workspace, instanceRoot, "APPLYING", states);
            journalWritten = true;
            for (RestoreState state : states) {
                Path target = safeChild(instanceRoot, state.name());
                if (state.originalExists()) Files.move(target, safeChild(rollback, state.name()));
                Files.move(safeChild(stagedContent, state.name()), target);
            }
            writeJournal(workspace, instanceRoot, "COMMITTED", states);
            committed = true;
        } catch (IOException | RuntimeException restoreError) {
            if (journalWritten) {
                try {
                    recover(instanceRoot);
                } catch (IOException rollbackError) {
                    restoreError.addSuppressed(rollbackError);
                    LOGGER.error("Restore rollback data was retained at {}", workspace, rollbackError);
                }
            }
            throw restoreError;
        } finally {
            if (committed || !journalWritten) {
                try {
                    cleanup(workspace);
                } catch (IOException cleanupError) {
                    LOGGER.warn("Cannot remove restore workspace {}", workspace, cleanupError);
                }
            }
        }
    }

    /** Idempotently restores the old directories after any interrupted rename. */
    static void recover(Path instanceDirectory) throws IOException {
        Path instanceRoot = instanceDirectory.toAbsolutePath().normalize();
        Path workspace = safeChild(instanceRoot, WORKSPACE);
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        Path journalFile = safeChild(workspace, JOURNAL);
        if (!Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
            Path rollback = safeChild(workspace, "rollback");
            if (Files.isDirectory(rollback)) {
                try (var entries = Files.list(rollback)) {
                    if (entries.findAny().isPresent()) throw new IOException("Restore journal is missing; original files were retained");
                }
            }
            cleanup(workspace);
            return;
        }
        RestoreJournal journal = readJournal(journalFile, instanceRoot);
        if (!journal.committed()) {
            for (RestoreState state : journal.states().reversed()) {
                Path target = safeChild(instanceRoot, state.name());
                Path original = safeChild(workspace, "rollback/" + state.name());
                Path incoming = safeChild(workspace, "content/" + state.name());
                if (state.originalExists()) {
                    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                        FileUtil.deleteDirectory(target);
                        Files.move(original, target);
                    } else if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Original restore directory is missing: " + state.name());
                    }
                } else if (!Files.exists(incoming, LinkOption.NOFOLLOW_LINKS)) {
                    FileUtil.deleteDirectory(target);
                }
            }
        }
        cleanup(workspace);
    }

    private static RestoreJournal readJournal(Path file, Path instanceRoot) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64 * 1024) {
            throw new IOException("World restore journal is invalid");
        }
        try {
            JsonObject json = HttpUtil.readJson(file.toFile());
            if (json.get("schemaVersion").getAsInt() != 1
                    || !Path.of(json.get("instanceRoot").getAsString()).toAbsolutePath().normalize().equals(instanceRoot)) {
                throw new IOException("World restore journal belongs to another instance or schema");
            }
            String phase = json.get("phase").getAsString();
            if (!Set.of("APPLYING", "COMMITTED").contains(phase)) throw new IOException("Invalid world restore phase");
            List<RestoreState> states = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonElement entry : json.getAsJsonArray("entries")) {
                JsonObject item = entry.getAsJsonObject();
                String name = item.get("name").getAsString();
                BackupEntry.Content.fromDirectoryName(name);
                if (!names.add(name) || !item.get("originalExists").getAsJsonPrimitive().isBoolean()) {
                    throw new IOException("Invalid world restore entry");
                }
                states.add(new RestoreState(name, item.get("originalExists").getAsBoolean()));
            }
            if (!names.contains("saves")) throw new IOException("World restore journal has no saves entry");
            return new RestoreJournal(phase.equals("COMMITTED"), List.copyOf(states));
        } catch (RuntimeException invalid) {
            throw new IOException("World restore journal is damaged", invalid);
        }
    }

    private static void writeJournal(Path workspace, Path instanceRoot, String phase,
                                     List<RestoreState> states) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("instanceRoot", instanceRoot.toString());
        json.addProperty("phase", phase);
        JsonArray entries = new JsonArray();
        for (RestoreState state : states) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", state.name());
            entry.addProperty("originalExists", state.originalExists());
            entries.add(entry);
        }
        json.add("entries", entries);
        Path file = safeChild(workspace, JOURNAL);
        HttpUtil.writeJson(file.toFile(), json);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void cleanup(Path workspace) throws IOException {
        // Delete the journal last so a crash during cleanup still has an unambiguous state.
        FileUtil.deleteDirectory(safeChild(workspace, "content"));
        FileUtil.deleteDirectory(safeChild(workspace, "rollback"));
        Files.deleteIfExists(safeChild(workspace, JOURNAL));
        FileUtil.deleteDirectory(workspace);
    }

    private static Path safeChild(Path root, String child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(child).normalize();
        if (!result.startsWith(normalizedRoot) || result.equals(normalizedRoot)) {
            throw new IOException("Path escapes restore root: " + child);
        }
        FileUtil.validateExistingAncestors(normalizedRoot, result);
        return result;
    }

    private record RestoreState(String name, boolean originalExists) { }
    private record RestoreJournal(boolean committed, List<RestoreState> states) { }
}
