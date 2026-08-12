package com.ecl.launch;

import com.ecl.game.VersionMetadata;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which Java feature version a game version needs. Prefers the explicit
 * {@code javaVersion.majorVersion} declared in the metadata; when the metadata omits it, falls back
 * to recognising well-known release and snapshot version shapes.
 */
final class JavaVersionRequirement {

    private static final Pattern RELEASE_VERSION_PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+_].*)?$");
    private static final Pattern SNAPSHOT_VERSION_PATTERN =
            Pattern.compile("^(\\d{2})w(\\d{2})([a-z].*)?$", Pattern.CASE_INSENSITIVE);

    private JavaVersionRequirement() {
    }

    static int forMetadata(VersionMetadata metadata) {
        if (metadata == null) {
            return 0;
        }
        if (metadata.javaMajorVersion() > 0) {
            return metadata.javaMajorVersion();
        }
        return inferFromVersionId(metadata.id());
    }

    static int inferFromVersionId(String versionId) {
        int[] release = parseReleaseVersion(versionId);
        if (release != null) {
            int minor = release[1];
            int patch = release[2];
            if (minor > 20 || minor == 20 && patch >= 5) {
                return 21;
            }
            return minor >= 18 ? 17 : 8;
        }

        int[] snapshot = parseSnapshotVersion(versionId);
        if (snapshot == null) {
            return 8;
        }
        int year = snapshot[0];
        int week = snapshot[1];
        if (year > 24 || year == 24 && week >= 14) {
            return 21;
        }
        return year > 21 || year == 21 && week >= 37 ? 17 : 8;
    }

    private static int[] parseReleaseVersion(String id) {
        if (id == null) {
            return null;
        }
        Matcher matcher = RELEASE_VERSION_PATTERN.matcher(id.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return new int[]{major, minor, patch};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int[] parseSnapshotVersion(String id) {
        if (id == null) {
            return null;
        }
        Matcher matcher = SNAPSHOT_VERSION_PATTERN.matcher(id.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))
        };
    }
}