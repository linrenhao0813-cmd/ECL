package com.ecl.modrinth.model;

import com.ecl.modrinth.instance.ModLoader;

public record ModCompatibility(String minecraftVersion, ModLoader loader) {
    public ModCompatibility {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        if (loader == null || !loader.supportsMods()) {
            throw new IllegalArgumentException("A supported mod loader is required");
        }
    }
}
