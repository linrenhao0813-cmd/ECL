package com.ecl.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static String getNativeClassifier() {
        return PlatformUtil.current().minecraftName() + "-"
                + nativeArchitecture(System.getProperty("os.arch", ""));
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
        Path rootPath = root.getCanonicalFile().toPath();
        Path candidate;
        try {
            // Normalize backslashes so Windows-style separators cannot smuggle in ".." escapes.
            candidate = rootPath.resolve(relativePath.replace('\\', '/')).normalize()
                    .toFile().getCanonicalFile().toPath();
        } catch (InvalidPathException error) {
            throw new IOException("依赖路径无效: " + relativePath, error);
        }
        if (!candidate.startsWith(rootPath)) {
            throw new IOException("依赖路径越界: " + relativePath);
        }
        return candidate.toFile();
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
