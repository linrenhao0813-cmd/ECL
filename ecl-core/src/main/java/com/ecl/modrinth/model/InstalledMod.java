package com.ecl.modrinth.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;

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
        Instant updatedAt,
        Set<String> requiredByProjectIds,
        boolean dependencyMetadataKnown
) {
    public InstalledMod {
        requiredByProjectId = requiredByProjectId == null ? "" : requiredByProjectId;
        requiredByProjectIds = requiredByProjectIds == null
                ? requiredByProjectId.isBlank()
                        ? Set.of() : Set.of(requiredByProjectId)
                : Set.copyOf(requiredByProjectIds);
        requiredByProjectId = requiredByProjectIds.contains(requiredByProjectId)
                ? requiredByProjectId
                : requiredByProjectIds.stream().sorted().findFirst().orElse("");
    }

    public InstalledMod(UUID instanceId, String projectId, String versionId, String projectSlug,
                        String displayName, String versionNumber, String fileName, Path relativePath,
                        String sha1, String sha512, long fileSize, String minecraftVersion,
                        String loader, String versionType, boolean enabled, boolean dependency,
                        String requiredByProjectId, Instant installedAt, Instant updatedAt) {
        this(instanceId, projectId, versionId, projectSlug, displayName, versionNumber, fileName,
                relativePath, sha1, sha512, fileSize, minecraftVersion, loader, versionType,
                enabled, dependency, requiredByProjectId, installedAt, updatedAt, null, true);
    }

    public InstalledMod(UUID instanceId, String projectId, String versionId, String projectSlug,
                        String displayName, String versionNumber, String fileName, Path relativePath,
                        String sha1, String sha512, long fileSize, String minecraftVersion,
                        String loader, String versionType, boolean enabled, boolean dependency,
                        String requiredByProjectId, Instant installedAt, Instant updatedAt, Set<String> requiredByProjectIds) {
        this(instanceId, projectId, versionId, projectSlug, displayName, versionNumber, fileName,
                relativePath, sha1, sha512, fileSize, minecraftVersion, loader, versionType,
                enabled, dependency, requiredByProjectId, installedAt, updatedAt, requiredByProjectIds, true);
    }

    public InstalledMod withRequiredByProjectIds(Set<String> owners) {
        return new InstalledMod(instanceId, projectId, versionId, projectSlug, displayName,
                versionNumber, fileName, relativePath, sha1, sha512, fileSize, minecraftVersion,
                loader, versionType, enabled, dependency, requiredByProjectId, installedAt, updatedAt,
                owners, dependencyMetadataKnown);
    }

    public InstalledMod withDependencyMetadataKnown(boolean known) {
        return new InstalledMod(instanceId, projectId, versionId, projectSlug, displayName,
                versionNumber, fileName, relativePath, sha1, sha512, fileSize, minecraftVersion,
                loader, versionType, enabled, dependency, requiredByProjectId, installedAt, updatedAt,
                requiredByProjectIds, known);
    }
}
