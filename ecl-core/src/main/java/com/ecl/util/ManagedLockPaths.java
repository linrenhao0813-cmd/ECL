package com.ecl.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Stable lock-file locations for managed operations. */
public final class ManagedLockPaths {
    private ManagedLockPaths() {
    }

    public static Path instanceOperation(Path instanceDirectory) {
        Path normalized = instanceDirectory.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            return normalized.resolve(".ecl").resolve("lifecycle.lock");
        }
        String identity = normalized.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            identity = identity.toLowerCase(Locale.ROOT);
        }
        return parent.resolve(".ecl-instance-locks")
                .resolve(sha256(identity) + ".lifecycle.lock");
    }

    public static Path versionDownload(Path versionsDirectory, String versionId)
            throws java.io.IOException {
        FileUtil.requireSafeVersionId(versionId);
        return versionsDirectory.toAbsolutePath().normalize()
                .resolve(".ecl-version-locks").resolve(versionId + ".lock");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
