package com.ecl.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, hardened TAR extractor used for launcher-managed Java runtimes.
 *
 * <p>Unlike invoking the system {@code tar} binary, this reader validates every entry before
 * touching the filesystem: absolute paths, {@code ..} segments, path escapes, symbolic links,
 * hard links and unknown entry types are all rejected.  Only regular files and directories are
 * extracted, so even a tampered archive cannot write outside the staging directory.</p>
 *
 * <p>Supported formats: POSIX ustar (including GNU long-name entries). Per-file PAX path
 * overrides are supported and pass through the same containment validation as normal names;
 * global PAX path overrides are ignored.</p>
 */
public final class TarUtil {

    private static final int RECORD_SIZE = 512;
    private static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024; // 4 GB 解压总量
    private static final long MAX_SINGLE_BYTES = 1024L * 1024 * 1024;    // 1 GB 单文件
    private static final int MAX_ENTRIES = 100_000;

    private TarUtil() {
    }

    /** Extracts a gzip-compressed tar archive into {@code target}, validating every entry. */
    public static void extractGzipTar(Path archive, Path target) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             GZIPInputStream gzip = new GZIPInputStream(raw)) {
            extractTar(gzip, target);
        }
    }

    private static void extractTar(InputStream input, Path target) throws IOException {
        target = target.toAbsolutePath().normalize();
        Files.createDirectories(target);
        target = target.toRealPath();
        byte[] header = new byte[RECORD_SIZE];
        String pendingLongName = null;
        String pendingPaxName = null;
        long totalBytes = 0;
        int entryCount = 0;
        Map<Path, Integer> directoryModes = new LinkedHashMap<>();

        while (true) {
            int read = readFully(input, header);
            if (read == 0) {
                break; // clean EOF
            }
            if (read < RECORD_SIZE) {
                throw new IOException("TAR 归档头部被截断");
            }
            if (isZeroBlock(header)) {
                // 结束标记：允许一个 512 字节零块后接 EOF，或两个零块。
                read = readFully(input, header);
                if (read == 0 || isZeroBlock(header)) {
                    break;
                }
                throw new IOException("TAR 归档结构异常");
            }

            validateChecksum(header);
            if (++entryCount > MAX_ENTRIES) {
                throw new IOException("TAR 归档条目数量超过安全限制");
            }

            String name = readName(header);
            long size = readOctal(header, 124, 11);
            int mode = (int) readOctal(header, 100, 8);
            int typeFlag = header[156] & 0xFF;
            if (size > MAX_SINGLE_BYTES || totalBytes > MAX_TOTAL_BYTES - size) {
                throw new IOException("TAR 条目超出解压大小限制: " + name);
            }
            totalBytes += size;

            if (typeFlag == 'L') {
                // GNU long name: 真实名称存放在条目数据中，下一项生效。
                pendingLongName = readLongNamePayload(input, size, name);
                skipPadding(input, size);
                continue;
            }
            if (typeFlag == 'K') {
                // GNU long link name: 我们不创建链接，直接跳过数据。
                if (size < 0 || size > 1024 * 1024) {
                    throw new IOException("TAR 长链接名称长度异常: " + name);
                }
                skipFully(input, size);
                skipPadding(input, size);
                continue;
            }
            if (typeFlag == 'x' || typeFlag == 'g') {
                // 仅逐文件 PAX 头的 path 对下一项生效；全局 path 会改变所有后续条目，
                // 对运行时包没有必要，忽略它能保持更窄的解压语义。
                String pathOverride = readPaxPath(input, size);
                skipPadding(input, size);
                if (typeFlag == 'x' && pathOverride != null && !pathOverride.isBlank()) {
                    pendingPaxName = pathOverride;
                }
                continue;
            }

            String entryName = pendingPaxName != null
                    ? pendingPaxName
                    : (pendingLongName != null ? pendingLongName : name);
            pendingPaxName = null;
            pendingLongName = null;

            if (typeFlag == '5' || entryName.endsWith("/")) {
                if (size != 0) {
                    throw new IOException("TAR 目录条目包含异常数据: " + entryName);
                }
                Path dir = resolveEntry(target, entryName);
                Files.createDirectories(dir);
                directoryModes.put(dir, mode);
                skipFully(input, size);
                skipPadding(input, size);
                continue;
            }

            if (typeFlag != '0' && typeFlag != '\0' && typeFlag != '7') {
                // 符号链接、硬链接、稀疏文件及未知类型一律拒绝。
                throw new IOException("TAR 归档包含不支持的条目类型 (" + typeFlag + "): " + entryName);
            }
            if (entryName.isEmpty()) {
                throw new IOException("TAR 归档包含空条目名称");
            }

            Path outPath = resolveEntry(target, entryName);
            Files.createDirectories(outPath.getParent());
            readPayload(input, size, outPath, entryName);
            applyMode(outPath, mode);
            skipPadding(input, size);
        }

        // Apply directory modes after all children have been created. A read-only directory
        // appearing early in a valid archive must not prevent extraction of its descendants.
        var modes = new java.util.ArrayList<>(directoryModes.entrySet());
        for (int i = modes.size() - 1; i >= 0; i--) {
            applyMode(modes.get(i).getKey(), modes.get(i).getValue());
        }
    }

    private static Path resolveEntry(Path target, String entryName) throws IOException {
        String normalized = entryName.replace('\\', '/');
        Path candidate = target.resolve(normalized).normalize();
        if (candidate.equals(target) || !candidate.startsWith(target)) {
            throw new IOException("TAR 条目越出解压目录: " + entryName);
        }
        // Follow any already-existing parent links as a second boundary check. The staging
        // directory starts empty, but this also makes the helper safe if it is reused elsewhere.
        Path canonical = candidate.toFile().getCanonicalFile().toPath();
        if (!canonical.startsWith(target)) {
            throw new IOException("TAR 条目经由链接越出解压目录: " + entryName);
        }
        return canonical;
    }

    /** Reads a GNU long-name payload (the real entry name). */
    private static String readLongNamePayload(InputStream input, long size, String entryName)
            throws IOException {
        if (size <= 0 || size > 1024 * 1024) {
            throw new IOException("TAR 长文件名长度异常: " + entryName);
        }
        byte[] buffer = new byte[(int) size];
        int got = readFully(input, buffer);
        if (got != size) {
            throw new IOException("TAR 长文件名数据被截断: " + entryName);
        }
        int length = 0;
        while (length < got && buffer[length] != 0) {
            length++;
        }
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * Parses a PAX extended header payload and returns its {@code path} override, or null when
     * absent.  The returned path is validated against the extraction root later, so a tampered
     * override cannot escape the target directory.
     */
    private static String readPaxPath(InputStream input, long size) throws IOException {
        if (size <= 0 || size > 1024 * 1024) {
            throw new IOException("TAR PAX 头长度异常");
        }
        byte[] buffer = new byte[(int) size];
        int got = readFully(input, buffer);
        if (got != size) {
            throw new IOException("TAR PAX 头数据被截断");
        }
        String path = null;
        int offset = 0;
        while (offset < got) {
            int space = indexOf(buffer, (byte) ' ', offset, got);
            if (space < 0) {
                throw new IOException("TAR PAX 记录缺少长度分隔符");
            }
            int recordLength;
            try {
                recordLength = Integer.parseInt(new String(
                        buffer, offset, space - offset, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new IOException("TAR PAX 记录长度无效", e);
            }
            if (recordLength <= 0 || offset + recordLength > got) {
                throw new IOException("TAR PAX 记录长度越界");
            }
            int recordEnd = offset + recordLength;
            int eq = indexOf(buffer, (byte) '=', space + 1, recordEnd);
            if (eq > space + 1) {
                String key = new String(buffer, space + 1, eq - space - 1,
                        StandardCharsets.UTF_8);
                int valueEnd = recordEnd;
                if (valueEnd > eq + 1 && buffer[valueEnd - 1] == '\n') valueEnd--;
                if (valueEnd > eq + 1 && buffer[valueEnd - 1] == '\r') valueEnd--;
                String value = new String(buffer, eq + 1, valueEnd - eq - 1,
                        StandardCharsets.UTF_8);
                if ("path".equals(key)) {
                    path = value;
                }
            }
            offset += recordLength;
        }
        return path;
    }

    /** Reads exactly {@code size} bytes; writes them to {@code outPath} when non-null. */
    private static long readPayload(InputStream input, long size, Path outPath, String entryName)
            throws IOException {
        long remaining = size;
        if (outPath == null) {
            skipFully(input, size);
            return 0;
        }
        try (OutputStream out = Files.newOutputStream(outPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int got = readAtMost(input, buffer, 0, wanted);
                if (got <= 0) {
                    throw new IOException("TAR 归档数据被截断: " + entryName);
                }
                out.write(buffer, 0, got);
                remaining -= got;
            }
        } catch (IOException error) {
            try {
                Files.deleteIfExists(outPath);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        return size;
    }

    private static String readName(byte[] header) throws IOException {
        String prefix = readString(header, 345, 155);
        String name = readString(header, 0, 100);
        if (name.isEmpty()) {
            throw new IOException("TAR 条目名称为空");
        }
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] bytes, byte needle, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] == needle) return i;
        }
        return -1;
    }

    private static long readOctal(byte[] header, int offset, int length) throws IOException {
        long value = 0;
        int end = offset + length;
        int i = offset;
        while (i < end && (header[i] == ' ' || header[i] == 0)) {
            i++;
        }
        boolean started = false;
        for (; i < end; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > '7') {
                throw new IOException("TAR 条目大小字段无效");
            }
            started = true;
            try {
                value = Math.addExact(Math.multiplyExact(value, 8), b - '0');
            } catch (ArithmeticException overflow) {
                throw new IOException("TAR 条目大小字段溢出", overflow);
            }
        }
        if (!started) {
            throw new IOException("TAR 条目大小字段为空");
        }
        return value;
    }

    private static void validateChecksum(byte[] header) throws IOException {
        long expected = readOctal(header, 148, 8);
        long actual = 0;
        for (int i = 0; i < header.length; i++) {
            actual += i >= 148 && i < 156 ? ' ' : header[i] & 0xff;
        }
        if (expected != actual) {
            throw new IOException("TAR 归档头部校验和无效");
        }
    }

    private static void applyMode(Path path, int mode) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(path, permissions);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                int got = readAtMost(input, new byte[8192], 0, (int) Math.min(8192, remaining));
                if (got <= 0) {
                    throw new IOException("TAR 归档数据被截断");
                }
                remaining -= got;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void skipPadding(InputStream input, long payloadSize) throws IOException {
        long padding = (RECORD_SIZE - payloadSize % RECORD_SIZE) % RECORD_SIZE;
        if (padding > 0) {
            skipFully(input, padding);
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int got = readAtMost(input, buffer, total, buffer.length - total);
            if (got <= 0) {
                break;
            }
            total += got;
        }
        return total;
    }

    private static int readAtMost(InputStream input, byte[] buffer, int offset, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int got = input.read(buffer, offset + total, length - total);
            if (got <= 0) {
                break;
            }
            total += got;
        }
        return total;
    }
}
