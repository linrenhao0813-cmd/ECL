package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scans locally installed Loader profiles without owning remote version metadata. */
final class LocalVersionProfileScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalVersionProfileScanner.class);

    List<VersionManager.LocalVersionProfile> scan() {
        File versionsDirectory = ECLConfig.getVersionsDir();
        File[] directories = versionsDirectory.listFiles(File::isDirectory);
        if (directories == null) {
            return List.of();
        }
        List<VersionManager.LocalVersionProfile> profiles = new ArrayList<>();
        for (File directory : directories) {
            String profileId = directory.getName();
            File jsonFile = new File(directory, profileId + ".json");
            if (!jsonFile.isFile()) {
                continue;
            }
            try {
                JsonObject json = HttpUtil.readJson(jsonFile);
                String loader = JsonUtil.getString(json, "eclModLoader", "").toLowerCase(Locale.ROOT);
                if (loader.isBlank()) {
                    loader = detectLoader(json);
                }
                if (loader.isBlank()) {
                    continue;
                }
                String minecraftVersion = JsonUtil.getString(json, "eclMinecraftVersion", "");
                if (minecraftVersion.isBlank()) {
                    minecraftVersion = JsonUtil.getString(json, "inheritsFrom", "");
                }
                if (minecraftVersion.isBlank()) {
                    continue;
                }
                profiles.add(new VersionManager.LocalVersionProfile(profileId, minecraftVersion, loader));
            } catch (IOException e) {
                LOGGER.warn("Failed to inspect local version profile {}", profileId, e);
            }
        }
        profiles.sort(Comparator.comparing(VersionManager.LocalVersionProfile::minecraftVersion).reversed()
                .thenComparing(VersionManager.LocalVersionProfile::loader)
                .thenComparing(VersionManager.LocalVersionProfile::profileId));
        return List.copyOf(profiles);
    }

    static String displayName(String loader) {
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> loader;
        };
    }

    private static String detectLoader(JsonObject json) {
        String mainClass = JsonUtil.getString(json, "mainClass", "").toLowerCase(Locale.ROOT);
        if (mainClass.contains("fabricmc")) return "fabric";
        if (mainClass.contains("quiltmc")) return "quilt";
        if (mainClass.contains("neoforge")) return "neoforge";
        if (mainClass.contains("forge")) return "forge";
        JsonArray libraries = json.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement element : libraries) {
                if (!element.isJsonObject()) continue;
                String coordinate = JsonUtil.getString(element.getAsJsonObject(), "name", "")
                        .toLowerCase(Locale.ROOT);
                if (coordinate.contains("fabric-loader")) return "fabric";
                if (coordinate.contains("quilt-loader")) return "quilt";
                if (coordinate.contains("neoforge")) return "neoforge";
                if (coordinate.contains("minecraftforge") || coordinate.contains("forge:forge")) return "forge";
            }
        }
        return "";
    }
}
