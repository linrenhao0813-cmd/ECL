package com.ecl.game;

/** Default policy used when an instance has no explicit run-directory override. */
public enum DefaultIsolationType {
    ALWAYS,
    MODDED,
    NEVER;

    public static DefaultIsolationType parse(String value) {
        if (value == null || value.isBlank()) {
            return MODDED;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MODDED;
        }
    }
}
