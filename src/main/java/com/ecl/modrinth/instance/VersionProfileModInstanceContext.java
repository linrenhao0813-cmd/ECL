package com.ecl.modrinth.instance;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.TextUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Adapts ECL's existing launchable version profile into the Modrinth instance boundary.
 */
public final class VersionProfileModInstanceContext implements ModInstanceContext {
    private final UUID instanceId;
    private final String profileId;
    private final String minecraftVersion;
    private final ModLoader loader;
    private final Path gameDirectory;

    private VersionProfileModInstanceContext(UUID instanceId, String profileId, String minecraftVersion,
                                             ModLoader loader, Path gameDirectory) {
        this.instanceId = instanceId;
        this.profileId = profileId;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.gameDirectory = gameDirectory;
    }

    public static VersionProfileModInstanceContext load(String profileId, Path versionMetadataDirectory,
                                                        Path configuredGameRoot) throws IOException {
        String normalizedProfileId = requireText(profileId, "profileId");
        Path metadataRoot = Objects.requireNonNull(versionMetadataDirectory, "versionMetadataDirectory")
                .toAbsolutePath().normalize();
        Path gameRoot = Objects.requireNonNull(configuredGameRoot, "configuredGameRoot")
                .toAbsolutePath().normalize();
        JsonObject profile = readProfile(metadataRoot, normalizedProfileId);

        String minecraftVersion = resolveMinecraftVersion(metadataRoot, normalizedProfileId, profile, new HashSet<>());
        ModLoader loader = resolveLoader(profile);
        String safeDirectoryName = TextUtil.replaceInvalidFilenameChars(normalizedProfileId.trim());
        if (safeDirectoryName.isBlank()) {
            throw new IOException("Invalid version profile id: " + profileId);
        }
        Path gameDirectory = gameRoot.resolve("versions").resolve(safeDirectoryName).normalize();
        Path instancesRoot = gameRoot.resolve("versions").normalize();
        if (!gameDirectory.startsWith(instancesRoot)) {
            throw new IOException("Version profile directory escapes game root: " + profileId);
        }

        String identityPath = gameDirectory.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            identityPath = identityPath.toLowerCase(Locale.ROOT);
        }
        UUID instanceId = UUID.nameUUIDFromBytes(("ecl-version-profile:" + identityPath)
                .getBytes(StandardCharsets.UTF_8));
        return new VersionProfileModInstanceContext(instanceId, normalizedProfileId, minecraftVersion,
                loader, gameDirectory);
    }

    private static JsonObject readProfile(Path metadataRoot, String profileId) throws IOException {
        Path profileFile = metadataRoot.resolve(profileId).resolve(profileId + ".json").normalize();
        if (!profileFile.startsWith(metadataRoot) || !profileFile.toFile().isFile()) {
            throw new IOException("Missing version profile JSON: " + profileFile);
        }
        return HttpUtil.readJson(profileFile.toFile());
    }

    private static String resolveMinecraftVersion(Path metadataRoot, String profileId, JsonObject profile,
                                                  Set<String> visited) throws IOException {
        if (!visited.add(profileId)) {
            throw new IOException("Circular version inheritance while resolving instance: " + visited);
        }
        String explicit = JsonUtil.getString(profile, "eclMinecraftVersion", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        String parentId = JsonUtil.getString(profile, "inheritsFrom", "");
        if (!parentId.isBlank()) {
            JsonObject parent = readProfile(metadataRoot, parentId);
            return resolveMinecraftVersion(metadataRoot, parentId, parent, visited);
        }
        String id = JsonUtil.getString(profile, "id", profileId);
        return id.isBlank() ? profileId : id;
    }

    private static ModLoader resolveLoader(JsonObject profile) {
        ModLoader explicit = ModLoader.fromApiName(JsonUtil.getString(profile, "eclModLoader", ""));
        if (explicit.supportsMods()) {
            return explicit;
        }

        String mainClass = JsonUtil.getString(profile, "mainClass", "").toLowerCase(Locale.ROOT);
        if (mainClass.contains("fabricmc")) {
            return ModLoader.FABRIC;
        }
        if (mainClass.contains("quiltmc")) {
            return ModLoader.QUILT;
        }
        if (mainClass.contains("neoforge")) {
            return ModLoader.NEOFORGE;
        }
        if (mainClass.contains("forge")) {
            return ModLoader.FORGE;
        }
        if (profile.has("libraries") && profile.get("libraries").isJsonArray()) {
            for (JsonElement element : profile.getAsJsonArray("libraries")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String coordinate = JsonUtil.getString(element.getAsJsonObject(), "name", "")
                        .toLowerCase(Locale.ROOT);
                if (coordinate.contains("fabric-loader")) {
                    return ModLoader.FABRIC;
                }
                if (coordinate.contains("quilt-loader")) {
                    return ModLoader.QUILT;
                }
                if (coordinate.contains("neoforge")) {
                    return ModLoader.NEOFORGE;
                }
                if (coordinate.contains("minecraftforge") || coordinate.contains("forge:forge")) {
                    return ModLoader.FORGE;
                }
            }
        }
        return ModLoader.NONE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public UUID instanceId() {
        return instanceId;
    }

    @Override
    public String profileId() {
        return profileId;
    }

    @Override
    public String minecraftVersion() {
        return minecraftVersion;
    }

    @Override
    public ModLoader loader() {
        return loader;
    }

    @Override
    public Path gameDirectory() {
        return gameDirectory;
    }

    @Override
    public Path modsDirectory() {
        return gameDirectory.resolve("mods");
    }
}
