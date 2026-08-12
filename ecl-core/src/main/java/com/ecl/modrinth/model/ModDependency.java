package com.ecl.modrinth.model;

public record ModDependency(
        String versionId,
        String projectId,
        String fileName,
        DependencyType type
) {
}
