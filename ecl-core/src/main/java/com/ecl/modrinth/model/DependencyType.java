package com.ecl.modrinth.model;

import java.util.Locale;

public enum DependencyType {
    REQUIRED,
    OPTIONAL,
    INCOMPATIBLE,
    EMBEDDED,
    UNKNOWN;

    public static DependencyType fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
