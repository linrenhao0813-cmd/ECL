package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.file.attribute.DosFileAttributes;
import java.util.UUID;

/** Centralizes MRPACK profile, instance and archive path safety rules. */
final class MrpackPathPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(MrpackPathPolicy.class);

    private MrpackPathPolicy() {
    }

    static String uniqueProfileId(String name, String version, Path gameRoot) {
        String raw = (name + "-" + version).trim();
        String base = TextUtil.replaceInvalidFilenameChars(raw)
                .replace(' ', '-').replaceAll("-+", "-")
                .replaceAll("[. ]+$", "");
        if (base.isBlank() || isWindowsReservedName(base)) {
            base = "modrinth-pack";
        }
        if (base.length() > 72) {
            String hash = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8))
                    .toString().substring(0, 8);
            base = base.substring(0, 63).replaceAll("[. ]+$", "") + "-" + hash;
        }
        Path versions = ECLConfig.getVersionsDir().toPath();
        Path gameVersions = gameRoot.resolve("versions");
        String candidate = base;
        int suffix = 2;
        while (Files.exists(versions.resolve(candidate))
                || Files.exists(gameVersions.resolve(candidate))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    static Path profileDirectory(String profileId) throws IOException {
        Path root = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        Path profileDir = safeResolve(root, profileId);
        validateExistingAncestors(root, profileDir);
        return profileDir;
    }

    static boolean deleteProfile(String profileId) {
        Path root = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(profileId).normalize();
        if (target.startsWith(root) && !target.equals(root)) {
            return deleteRecursively(target);
        }
        return false;
    }

    /**
     * Recursively deletes {@code root} and reports whether every entry was removed.
     * Failures are logged so silent data loss is visible; callers surface the boolean in UI.
     */
    static boolean deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return false;
        }
        boolean complete = true;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    complete = false;
                    LOGGER.warn("Failed to delete path while removing {}: {}", root, path, failure);
                }
            }
        } catch (IOException failure) {
            complete = false;
            LOGGER.warn("Failed to walk directory tree while removing {}", root, failure);
        }
        if (!complete) {
            LOGGER.warn("Directory removal incomplete for {}", root);
        }
        return complete;
    }

    static Path safeInstanceDirectory(Path gameRoot, String profileId) throws IOException {
        Path root = gameRoot.toAbsolutePath().normalize().resolve("versions").normalize();
        Path result = safeResolve(root, profileId);
        validateExistingAncestors(root, result);
        return result;
    }

    static Path safeResolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("整合包包含空文件路径");
        }
        String normalizedRelative = relative.replace('\\', '/');
        if (normalizedRelative.startsWith("/") || normalizedRelative.matches("^[A-Za-z]:.*")) {
            throw new IOException("整合包包含绝对路径: " + relative);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result;
        try {
            result = normalizedRoot.resolve(normalizedRelative).normalize();
        } catch (InvalidPathException invalid) {
            throw new IOException("整合包包含无效路径: " + relative, invalid);
        }
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("整合包包含越界路径: " + relative);
        }
        validateExistingAncestors(normalizedRoot, result);
        return result;
    }

    /**
     * Checks every existing component without following links. This is deliberately performed
     * before callers create parent directories, so an archive cannot turn a pre-existing link
     * into an escape to an arbitrary tree. DOS reparse points include junctions on Windows.
     */
    static void validateExistingAncestors(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IOException("整合包路径越界");
        }
        checkPathComponent(normalizedRoot, "整合包根目录");
        Path current = normalizedRoot;
        Path relative = normalizedRoot.relativize(normalizedCandidate);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                checkPathComponent(current, "整合包路径");
            }
        }
        if (Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
                && Files.exists(normalizedCandidate, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (!normalizedCandidate.toRealPath().startsWith(normalizedRoot.toRealPath())) {
                    throw new IOException("整合包路径解析后越界");
                }
            } catch (IOException error) {
                throw new IOException("无法验证整合包路径", error);
            }
        }
    }

    private static void checkPathComponent(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(label + "不能是符号链接: " + path.getFileName());
        }
        try {
            DosFileAttributes attrs = Files.readAttributes(path, DosFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isReparsePoint()) {
                throw new IOException(label + "不能是 Windows reparse point: " + path.getFileName());
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-DOS providers have no reparse-point attribute; symbolic links are still checked.
        }
    }

    private static boolean isWindowsReservedName(String value) {
        return TextUtil.isWindowsReservedName(value);
    }
}
