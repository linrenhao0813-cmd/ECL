package com.ecl.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;

public class FileUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileUtil.class);
    private static final int SHA1_BUFFER_SIZE = 64 * 1024;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public static String sha1(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }

        try (FileChannel channel = FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(SHA1_BUFFER_SIZE);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }

        byte[] hash = digest.digest();
        char[] chars = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[b >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(chars);
    }

    public static boolean verifySha1(File file, String expected) {
        try {
            return sha1(file).equalsIgnoreCase(expected);
        } catch (IOException e) {
            LOGGER.warn("Failed to verify SHA-1 for {}", file.getAbsolutePath(), e);
            return false;
        }
    }

    public static String sha1(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("SHA-1 input is null");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
        byte[] hash = digest.digest(data);
        char[] chars = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[b >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(chars);
    }

    public static boolean verifySha1(byte[] data, String expected) {
        return expected != null && !expected.isBlank() && sha1(data).equalsIgnoreCase(expected);
    }

    /**
     * Deletes {@code dir} without following symbolic links or Windows junctions. A reparse point
     * at the root or inside the tree is removed as a single node so a planted junction cannot
     * cause the launcher to walk and delete an unrelated directory.
     */
    public static void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isSymlinkOrReparsePoint(dir)) {
            Files.delete(dir);
            return;
        }
        Files.walkFileTree(dir, java.util.EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                            throws IOException {
                        if (!directory.equals(dir) && isSymlinkOrReparsePoint(directory)) {
                            Files.delete(directory);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException exc)
                            throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** True when {@code path} is a symbolic link or a Windows reparse point (including junctions). */
    public static boolean isSymlinkOrReparsePoint(Path path) {
        if (path == null) {
            return false;
        }
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        if (!isWindows()) {
            return false;
        }
        int attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString());
        return attributes != WinBase.INVALID_FILE_ATTRIBUTES
                && (attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    }

    public static String getNativeClassifier() {
        return "windows-" + nativeArchitecture(System.getProperty("os.arch", ""));
    }

    /** Normalize JVM architecture names to the suffixes used by Minecraft native classifiers. */
    public static String nativeArchitecture(String architecture) {
        String normalized = architecture == null ? "" : architecture.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("aarch64") || normalized.contains("arm64")) {
            return "arm64";
        }
        if (normalized.contains("x86_64") || normalized.contains("amd64")
                || normalized.equals("x64")) {
            return "x86_64";
        }
        if (normalized.contains("arm")) {
            return "arm32";
        }
        return "x86";
    }

    /**
     * Resolves a metadata-supplied relative path under {@code root}, refusing any value that
     * escapes the root via {@code ..} segments, absolute paths or mixed path separators.
     * Defends against malicious or tampered version metadata writing outside the libraries dir.
     *
     * @throws IOException when the path is blank or resolves outside {@code root}
     */
    public static File safeResolveUnder(File root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("依赖路径为空");
        }
        // Canonical paths also account for existing junctions/symbolic links. A purely lexical
        // startsWith check would allow <root>/linked-dir/file to reach outside the managed root.
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path canonicalRoot = root.getCanonicalFile().toPath();
        Path candidate;
        try {
            // Normalize backslashes so Windows-style separators cannot smuggle in ".." escapes.
            candidate = rootPath.resolve(relativePath.replace('\\', '/')).normalize();
            Path canonicalCandidate = candidate.toFile().getCanonicalFile().toPath();
            if (!canonicalCandidate.startsWith(canonicalRoot)) {
                throw new IOException("依赖路径越界: " + relativePath);
            }
        } catch (InvalidPathException error) {
            throw new IOException("依赖路径无效: " + relativePath, error);
        }
        if (!candidate.startsWith(rootPath)) {
            throw new IOException("依赖路径越界: " + relativePath);
        }
        return candidate.toFile();
    }

    /**
     * Validate all existing path components without following symbolic links or Windows reparse
     * points. The check is intentionally separate from lexical containment because a pre-existing
     * junction can redirect a later create/move operation outside the managed root.
     */
    public static void validateExistingAncestors(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IOException("Path escapes root: " + candidate);
        }
        Path current = normalizedRoot;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            validatePathComponent(current);
        }
        for (Path component : normalizedRoot.relativize(normalizedCandidate)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                validatePathComponent(current);
            }
        }
    }

    private static void validatePathComponent(Path path) throws IOException {
        if (isSymlinkOrReparsePoint(path)) {
            throw new IOException("Managed path cannot contain a symbolic link or reparse point: "
                    + path);
        }
        if (isWindows()) {
            int attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString());
            if (attributes == WinBase.INVALID_FILE_ATTRIBUTES) {
                throw new IOException("Unable to read Windows file attributes: " + path);
            }
        }
    }

    /**
     * Resolves a ZIP/JAR entry under {@code root}, rejecting absolute paths, drive letters,
     * NUL/colon smuggling, and any existing symlink or Windows reparse point on the path.
     */
    public static Path safeArchiveEntry(Path root, String entryName) throws IOException {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0
                || entryName.indexOf(':') >= 0) {
            throw new IOException("Archive contains an invalid entry name");
        }
        String relative = entryName.replace('\\', '/');
        while (relative.startsWith("./")) {
            relative = relative.substring(2);
        }
        if (relative.endsWith("/")) {
            relative = relative.substring(0, relative.length() - 1);
        }
        if (relative.isBlank() || ".".equals(relative) || "..".equals(relative)) {
            throw new IOException("Archive entry escapes the target directory: " + entryName);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target;
        try {
            target = safeResolveUnder(normalizedRoot.toFile(), relative).toPath();
        } catch (IOException invalid) {
            throw new IOException("Archive entry escapes the target directory: " + entryName, invalid);
        }
        if (target.equals(normalizedRoot)) {
            throw new IOException("Archive entry escapes the target directory: " + entryName);
        }
        validateExistingAncestors(normalizedRoot, target);
        return target;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Validates a version id before it is used to build file paths.  A version id must be a
     * single path segment: no separators, no drive letters / ADS colons, no NUL, and no leading
     * dot (which rules out {@code .} and {@code ..}).  This is the unified gate that keeps
     * {@code versionId}, {@code inheritsFrom} and {@code jar} fields from escaping the
     * {@code versions} directory, even when the version JSON is malicious or corrupted.
     *
     * @throws IOException when the id is blank or not a safe single path segment
     */
    public static void requireSafeVersionId(String versionId) throws IOException {
        if (versionId == null || versionId.isBlank()) {
            throw new IOException("版本 ID 为空");
        }
        if (versionId.indexOf('/') >= 0 || versionId.indexOf('\\') >= 0
                || versionId.indexOf(':') >= 0 || versionId.indexOf('\0') >= 0
                || versionId.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("非法的版本 ID: " + versionId);
        }
        if (versionId.equals(".") || versionId.equals("..") || versionId.startsWith(".")
                || versionId.endsWith(".") || versionId.endsWith(" ")) {
            throw new IOException("非法的版本 ID: " + versionId);
        }
        String stem = versionId.substring(0, versionId.indexOf('.') >= 0
                ? versionId.indexOf('.') : versionId.length()).toUpperCase(java.util.Locale.ROOT);
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")
                || stem.matches("COM[1-9]") || stem.matches("LPT[1-9]")) {
            throw new IOException("非法的版本 ID: " + versionId);
        }
    }

    /**
     * Validates {@code versionId} and returns its directory inside {@code versionsDir}
     * ({@code <versionsDir>/<versionId>}).  The result is guaranteed to stay under
     * {@code versionsDir}.
     *
     * @throws IOException when the id is invalid or resolves outside the versions directory
     */
    public static File safeVersionDirectory(File versionsDir, String versionId) throws IOException {
        requireSafeVersionId(versionId);
        return safeResolveUnder(versionsDir, versionId);
    }

    /**
     * Validates {@code versionId} and returns its JSON metadata file
     * ({@code <versionsDir>/<versionId>/<versionId>.json}).
     *
     * @throws IOException when the id is invalid or resolves outside the versions directory
     */
    public static File safeVersionJson(File versionsDir, String versionId) throws IOException {
        requireSafeVersionId(versionId);
        return safeResolveUnder(versionsDir, versionId + "/" + versionId + ".json");
    }

    /**
     * Validates {@code versionId} and returns its client jar
     * ({@code <versionsDir>/<versionId>/<versionId>.jar}).
     *
     * @throws IOException when the id is invalid or resolves outside the versions directory
     */
    public static File safeVersionJar(File versionsDir, String versionId) throws IOException {
        requireSafeVersionId(versionId);
        return safeResolveUnder(versionsDir, versionId + "/" + versionId + ".jar");
    }
}
