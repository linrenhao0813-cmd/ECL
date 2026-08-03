package com.ecl.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** ZIP helpers with explicit source roots and zip-slip-safe extraction. */
public final class ZipUtil {
    private static final int BUFFER_SIZE = 64 * 1024;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes, String currentEntry);
    }

    public record ArchivedFile(String path, long size) {
    }

    private ZipUtil() {
    }

    /**
     * Archives directories under their supplied top-level ZIP names. Missing directories are
     * represented as empty directories. Symbolic links are rejected so a backup cannot escape a
     * selected source tree.
     */
    public static List<ArchivedFile> zipDirectories(Map<String, Path> sources, Path archive,
                                                    ProgressListener listener) throws IOException {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("At least one source directory is required");
        }
        ProgressListener progress = listener == null ? (done, total, entry) -> { } : listener;
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        if (normalizedArchive.getParent() != null) Files.createDirectories(normalizedArchive.getParent());

        Map<String, Path> normalizedSources = new LinkedHashMap<>();
        for (Map.Entry<String, Path> source : sources.entrySet()) {
            String rootName = normalizeArchiveRoot(source.getKey());
            normalizedSources.put(rootName, source.getValue().toAbsolutePath().normalize());
        }
        long totalBytes = calculateTotalBytes(normalizedSources.values());
        long[] completed = {0L};
        List<ArchivedFile> files = new ArrayList<>();

        try (OutputStream raw = Files.newOutputStream(normalizedArchive);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            for (Map.Entry<String, Path> source : normalizedSources.entrySet()) {
                String rootName = source.getKey();
                Path root = source.getValue();
                if (!Files.exists(root)) {
                    putDirectory(zip, rootName + "/");
                    continue;
                }
                if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
                    throw new IOException("Backup source is not a regular directory: " + root);
                }
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (Files.isSymbolicLink(dir)) {
                            throw new IOException("Symbolic links are not supported in backups: " + dir);
                        }
                        Path relative = root.relativize(dir);
                        String name = rootName + "/" + toZipPath(relative);
                        putDirectory(zip, name.endsWith("/") ? name : name + "/");
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                            throw new IOException("Only regular files can be backed up: " + file);
                        }
                        String name = rootName + "/" + toZipPath(root.relativize(file));
                        ZipEntry entry = new ZipEntry(name);
                        entry.setTime(attrs.lastModifiedTime().toMillis());
                        zip.putNextEntry(entry);
                        long written = 0L;
                        byte[] buffer = new byte[BUFFER_SIZE];
                        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                zip.write(buffer, 0, read);
                                written += read;
                                completed[0] += read;
                                progress.onProgress(completed[0], totalBytes, name);
                            }
                        }
                        zip.closeEntry();
                        files.add(new ArchivedFile(name, written));
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(normalizedArchive);
            throw error;
        }
        progress.onProgress(totalBytes, totalBytes, "");
        return List.copyOf(files);
    }

    /** Extracts a ZIP into {@code destination}, rejecting entries that escape it or collide. */
    public static List<ArchivedFile> extractSafely(Path archive, Path destination,
                                                  ProgressListener listener) throws IOException {
        Path targetRoot = destination.toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        long totalBytes = Files.size(archive);
        long completed = 0L;
        ProgressListener progress = listener == null ? (done, total, entry) -> { } : listener;
        List<ArchivedFile> files = new ArrayList<>();
        Set<Path> extractedTargets = new HashSet<>();

        try (InputStream raw = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0
                        || (PlatformUtil.isWindows() && entryName.indexOf(':') >= 0)) {
                    throw new IOException("ZIP contains an invalid entry name");
                }
                Path target = targetRoot.resolve(entryName).normalize();
                if (!target.startsWith(targetRoot) || target.equals(targetRoot)) {
                    throw new IOException("ZIP entry escapes the target directory: " + entryName);
                }
                if (!extractedTargets.add(target)) {
                    throw new IOException("ZIP contains duplicate entries: " + entryName);
                }
                if (entry.isDirectory() || entryName.endsWith("/")) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent == null || !parent.startsWith(targetRoot)) {
                        throw new IOException("ZIP entry has an invalid parent: " + entryName);
                    }
                    Files.createDirectories(parent);
                    long written = 0L;
                    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            written += read;
                            completed += read;
                            progress.onProgress(completed, totalBytes, entryName);
                        }
                    }
                    files.add(new ArchivedFile(entryName, written));
                }
                zip.closeEntry();
            }
        }
        return List.copyOf(files);
    }

    private static long calculateTotalBytes(Iterable<Path> roots) throws IOException {
        long[] total = {0L};
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
                throw new IOException("Backup source is not a regular directory: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir)) {
                        throw new IOException("Symbolic links are not supported in backups: " + dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                        throw new IOException("Only regular files can be backed up: " + file);
                    }
                    total[0] = Math.addExact(total[0], attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return total[0];
    }

    private static String normalizeArchiveRoot(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid ZIP root name: " + value);
        }
        return value;
    }

    private static String toZipPath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static void putDirectory(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
    }
}
