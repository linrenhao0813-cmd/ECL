package com.ecl.modrinth.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

public record InstalledMod(
        UUID instanceId,
        String projectId,
        String versionId,
        String projectSlug,
        String displayName,
        String versionNumber,
        String fileName,
        Path relativePath,
        String sha1,
        String sha512,
        long fileSize,
        String minecraftVersion,
        String loader,
        String versionType,
        boolean enabled,
        boolean dependency,
        String requiredByProjectId,
        Instant installedAt,
        Instant updatedAt
) {
}
