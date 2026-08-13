package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModLoader;

/** Metadata read directly from a local mod JAR without requiring an online lookup. */
public record LocalModMeta(String id, String name, String version, ModLoader loader, boolean modded) {
    public LocalModMeta {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        version = version == null ? "" : version.trim();
        loader = loader == null ? ModLoader.NONE : loader;
    }

    public static LocalModMeta unknown() {
        return new LocalModMeta("", "", "", ModLoader.NONE, false);
    }
}
