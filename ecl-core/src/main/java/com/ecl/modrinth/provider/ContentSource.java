package com.ecl.modrinth.provider;

import java.util.Locale;

/** Normalized content source identity exposed to the UI. */
public enum ContentSource {
    MODRINTH,
    CURSEFORGE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return this == CURSEFORGE ? "CurseForge" : "Modrinth";
    }
}
