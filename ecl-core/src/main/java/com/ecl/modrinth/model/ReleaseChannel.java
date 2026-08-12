package com.ecl.modrinth.model;

import java.util.Locale;

public enum ReleaseChannel {
    RELEASE_ONLY,
    RELEASE_AND_BETA,
    ALL;

    public boolean allows(String versionType) {
        String type = versionType == null ? "" : versionType.toLowerCase(Locale.ROOT);
        return switch (this) {
            case RELEASE_ONLY -> "release".equals(type);
            case RELEASE_AND_BETA -> "release".equals(type) || "beta".equals(type);
            case ALL -> "release".equals(type) || "beta".equals(type) || "alpha".equals(type);
        };
    }

    public static ReleaseChannel forVersionType(String versionType) {
        String type = versionType == null ? "" : versionType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "alpha" -> ALL;
            case "beta" -> RELEASE_AND_BETA;
            default -> RELEASE_ONLY;
        };
    }
}
