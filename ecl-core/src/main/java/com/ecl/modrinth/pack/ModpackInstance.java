package com.ecl.modrinth.pack;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Persisted identity and location of an installed, updateable Modrinth pack. */
public record ModpackInstance(
        UUID instanceId,
        String profileId,
        String name,
        String currentVersion,
        String minecraftVersion,
        String loader,
        String projectId,
        String versionId,
        Path instanceDirectory
) {
    public ModpackInstance {
        instanceId = instanceId == null ? instanceIdFor(instanceDirectory) : instanceId;
        profileId = profileId == null ? "" : profileId;
        name = name == null ? profileId : name;
        currentVersion = currentVersion == null ? "" : currentVersion;
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        loader = loader == null ? "" : loader;
        projectId = projectId == null ? "" : projectId;
        versionId = versionId == null ? "" : versionId;
    }

    /** Uses the same stable identity as {@code VersionProfileModInstanceContext}. */
    public static UUID instanceIdFor(Path instanceDirectory) {
        if (instanceDirectory == null) {
            throw new IllegalArgumentException("instanceDirectory must not be null");
        }
        String identityPath = instanceDirectory.toAbsolutePath().normalize().toString()
                .toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("ecl-version-profile:" + identityPath)
                .getBytes(StandardCharsets.UTF_8));
    }
}
