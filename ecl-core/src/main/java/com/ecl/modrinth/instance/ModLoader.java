package com.ecl.modrinth.instance;

import java.util.Locale;

public enum ModLoader {
    FABRIC("fabric"),
    QUILT("quilt"),
    FORGE("forge"),
    NEOFORGE("neoforge"),
    NONE("");

    private final String apiName;

    ModLoader(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }

    public boolean supportsMods() {
        return this != NONE;
    }

    public static ModLoader fromApiName(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return switch (normalized) {
            case "fabric" -> FABRIC;
            case "quilt" -> QUILT;
            case "forge" -> FORGE;
            case "neoforge" -> NEOFORGE;
            default -> NONE;
        };
    }
}
