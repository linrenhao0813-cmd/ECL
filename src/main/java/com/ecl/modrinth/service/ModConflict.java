package com.ecl.modrinth.service;

import java.util.List;

public record ModConflict(
        String projectId,
        String conflictingProjectId,
        String message,
        List<String> dependencyPath
) {
    public ModConflict {
        dependencyPath = dependencyPath == null ? List.of() : List.copyOf(dependencyPath);
    }
}
