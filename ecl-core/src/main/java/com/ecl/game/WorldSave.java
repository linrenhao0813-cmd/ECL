package com.ecl.game;

import java.nio.file.Path;
import java.util.Objects;

/** A discovered Minecraft world plus the instance that owns its run directory. */
public record WorldSave(String name, Path directory, String instanceId,
                        String minecraftVersion, String modLoader, String modLoaderVersion,
                        long lastModified, WorldSaveSettings settings, boolean sharedDirectory) {
    public WorldSave(String name, Path directory, String instanceId,
                     String minecraftVersion, String modLoader, String modLoaderVersion,
                     long lastModified, WorldSaveSettings settings) {
        this(name, directory, instanceId, minecraftVersion, modLoader, modLoaderVersion,
                lastModified, settings, false);
    }

    public WorldSave {
        name = Objects.requireNonNull(name, "name");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        instanceId = instanceId == null ? "" : instanceId;
        minecraftVersion = minecraftVersion == null || minecraftVersion.isBlank()
                ? "未知版本" : minecraftVersion;
        modLoader = modLoader == null || modLoader.isBlank() ? "vanilla" : modLoader;
        modLoaderVersion = modLoaderVersion == null ? "" : modLoaderVersion;
        settings = settings == null ? WorldSaveSettings.defaults() : settings;
    }

    public String groupId() {
        return sharedDirectory ? "shared" : minecraftVersion + "\u0000"
                + modLoader.toLowerCase(java.util.Locale.ROOT);
    }

    public String loaderLabel() {
        if (sharedDirectory) return "共享目录";
        String label = switch (modLoader.toLowerCase(java.util.Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> "原版";
        };
        return modLoaderVersion.isBlank() ? label : label + " " + modLoaderVersion;
    }
}
