package com.ecl.modrinth.model;

import java.time.Instant;
import java.util.List;

public record ModVersion(
        String id,
        String projectId,
        String name,
        String versionNumber,
        String versionType,
        boolean featured,
        String status,
        List<String> gameVersions,
        List<String> loaders,
        Instant publishedAt,
        String changelog,
        List<ModFile> files,
        List<ModDependency> dependencies
) {
    public ModVersion {
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        files = files == null ? List.of() : List.copyOf(files);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
