package com.ecl.modrinth.transaction;

import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;

import java.nio.file.Path;

public record PlannedModFile(
        ModVersion version,
        ModFile file,
        Path targetPath,
        Path replacedPath,
        boolean dependency,
        String requiredByProjectId
) {
}
