package com.ecl.modrinth.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ModProject(
        String projectId,
        String slug,
        String title,
        String author,
        String description,
        String body,
        long downloads,
        long follows,
        URI iconUrl,
        Instant updatedAt,
        Set<String> categories,
        List<String> gameVersions,
        List<String> loaders,
        String license,
        String clientSide,
        String serverSide,
        URI projectUrl,
        URI sourceUrl,
        URI issuesUrl
) {
    public ModProject {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
    }
}
