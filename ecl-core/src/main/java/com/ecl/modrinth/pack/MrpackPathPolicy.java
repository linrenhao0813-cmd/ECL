package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path profileDir = root.resolve(profileId).normalize();
        if (!profileDir.startsWith(root)) {
            throw new IOException("整合包版本目录越界");
        }
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
        Path result = root.resolve(profileId).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("整合包实例目录越界");
        }
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
        Path result = normalizedRoot.resolve(normalizedRelative).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("整合包包含越界路径: " + relative);
        }
        return result;
    }

    private static boolean isWindowsReservedName(String value) {
        return TextUtil.isWindowsReservedName(value);
    }
}
