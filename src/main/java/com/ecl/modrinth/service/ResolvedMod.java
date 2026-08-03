package com.ecl.modrinth.service;

import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;

import java.util.List;

public record ResolvedMod(
        ModVersion version,
        ModFile file,
        boolean dependency,
        String requiredByProjectId,
        List<String> dependencyPath
) {
    public ResolvedMod {
        dependencyPath = dependencyPath == null ? List.of() : List.copyOf(dependencyPath);
    }
}
