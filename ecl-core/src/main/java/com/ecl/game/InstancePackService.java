package com.ecl.game;

import com.ecl.launch.GameProcessMarker;
import com.ecl.util.FileUtil;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.Messages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Local ZIP management for a resolved instance runtime directory; archives are never extracted. */
public final class InstancePackService {
    private final Path gameDirectory;
    private final Path directory;

    public InstancePackService(Path gameDirectory, String folder) {
        if (!"shaderpacks".equals(folder) && !"resourcepacks".equals(folder)) {
            throw new IllegalArgumentException("Unsupported pack directory: " + folder);
        }
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        directory = this.gameDirectory.resolve(folder);
    }

    public List<Path> list() throws IOException {
        FileUtil.validateExistingAncestors(gameDirectory, directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isZip(path.getFileName().toString()))
                    .filter(path -> !FileUtil.isSymlinkOrReparsePoint(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public void importPack(Path source) throws IOException {
        Path target = target(source.getFileName().toString());
        try (var lease = lock()) {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".ecl-import-", ".tmp");
            try {
                Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try (var zip = new ZipFile(temporary.toFile())) {
                    if (zip.size() == 0) throw new IOException(Messages.get("packs.invalidZip"));
                }
                FileUtil.validateExistingAncestors(gameDirectory, target);
                // No replacement: importing a same-name pack must preserve the existing file.
                Files.move(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public void remove(String name) throws IOException {
        Path target = target(name);
        try (var lease = lock()) {
            FileUtil.validateExistingAncestors(gameDirectory, target);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(Messages.get("packs.missing"));
            }
            Files.delete(target);
        }
    }

    private Path target(String name) throws IOException {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains(":") || !isZip(name)) {
            throw new IOException(Messages.get("packs.invalidName"));
        }
        Path target = directory.resolve(name).normalize();
        FileUtil.validateExistingAncestors(gameDirectory, target);
        return target;
    }

    private InstanceOperationLease lock() throws IOException {
        FileUtil.validateExistingAncestors(gameDirectory, directory);
        InstanceOperationLease lease = InstanceOperationLease.tryAcquire(gameDirectory);
        if (lease == null) throw new IOException(Messages.get("packs.busy"));
        try {
            if (GameProcessMarker.isRunning(gameDirectory)) throw new IOException(Messages.get("packs.running"));
            return lease;
        } catch (IOException | RuntimeException error) {
            try {
                lease.close();
            } catch (IOException closing) {
                error.addSuppressed(closing);
            }
            throw error;
        }
    }

    private static boolean isZip(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
