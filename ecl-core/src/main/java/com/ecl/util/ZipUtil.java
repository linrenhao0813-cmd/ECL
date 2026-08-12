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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** ZIP helpers with explicit source roots and zip-slip-safe extraction. */
public final class ZipUtil {
    private static final int BUFFER_SIZE = 64 * 1024;
    public static final ExtractionLimits DEFAULT_EXTRACTION_LIMITS = new ExtractionLimits(
            8L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024, 100_000, 200.0);

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes, String currentEntry);
    }

    public record ArchivedFile(String path, long size) {
    }

    public record ExtractionLimits(long maxTotalBytes, long maxSingleBytes,
                                   int maxEntries, double maxCompressionRatio) {
        public ExtractionLimits {
            if (maxTotalBytes <= 0 || maxSingleBytes <= 0 || maxEntries <= 0
                    || maxSingleBytes > maxTotalBytes || maxCompressionRatio <= 1.0) {
                throw new IllegalArgumentException("Invalid ZIP extraction limits");
            }
        }
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
        return extractSafely(archive, destination, listener, DEFAULT_EXTRACTION_LIMITS);
    }

    public static void validateArchive(Path archive, ExtractionLimits limits) throws IOException {
        Path source = archive.toAbsolutePath().normalize();
        ExtractionLimits safeLimits = limits == null ? DEFAULT_EXTRACTION_LIMITS : limits;
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            validateEntries(zip, safeLimits);
        }
    }

    /** Extracts with explicit resource limits, including unknown-size entries. */
    public static List<ArchivedFile> extractSafely(Path archive, Path destination,
                                                   ProgressListener listener,
                                                   ExtractionLimits limits) throws IOException {
        Path targetRoot = destination.toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        long totalBytes = Files.size(archive);
        long completed = 0L;
        ExtractionLimits safeLimits = limits == null ? DEFAULT_EXTRACTION_LIMITS : limits;
        ProgressListener progress = listener == null ? (done, total, entry) -> { } : listener;
        List<ArchivedFile> files = new ArrayList<>();
        Set<Path> extractedTargets = new HashSet<>();

        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            validateEntries(zip, safeLimits);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[BUFFER_SIZE];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
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
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                         OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (read > safeLimits.maxSingleBytes() - written) {
                                throw new IOException("ZIP entry exceeds the single-file limit: " + entryName);
                            }
                            if (read > safeLimits.maxTotalBytes() - completed) {
                                throw new IOException("ZIP extraction exceeds the total size limit");
                            }
                            output.write(buffer, 0, read);
                            written += read;
                            completed += read;
                            progress.onProgress(completed, totalBytes, entryName);
                        }
                    }
                    files.add(new ArchivedFile(entryName, written));
                }
            }
        }
        return List.copyOf(files);
    }

    private static void validateEntries(ZipFile zip, ExtractionLimits limits) throws IOException {
        int count = 0;
        long declaredTotal = 0L;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > limits.maxEntries()) {
                throw new IOException("ZIP entry count exceeds limit: " + limits.maxEntries());
            }
            if (entry.isDirectory()) continue;
            long size = entry.getSize();
            long compressed = entry.getCompressedSize();
            if (size > limits.maxSingleBytes()) {
                throw new IOException("ZIP entry exceeds the single-file limit: " + entry.getName());
            }
            if (size >= 0) {
                if (size > limits.maxTotalBytes() - declaredTotal) {
                    throw new IOException("ZIP declared size exceeds the total extraction limit");
                }
                declaredTotal += size;
            }
            if (size > 0 && compressed == 0) {
                throw new IOException("ZIP entry has an unsafe compression ratio: " + entry.getName());
            }
            if (size > 0 && compressed > 0
                    && (double) size / (double) compressed > limits.maxCompressionRatio()) {
                throw new IOException("ZIP entry compression ratio exceeds limit: " + entry.getName());
            }
        }
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
