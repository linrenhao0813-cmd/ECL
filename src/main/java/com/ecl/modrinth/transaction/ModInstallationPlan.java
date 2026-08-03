package com.ecl.modrinth.transaction;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.service.ModConflict;
import com.ecl.modrinth.service.ResolvedMod;

import java.util.List;

public record ModInstallationPlan(
        ModInstanceContext instance,
        ModVersion rootVersion,
        List<PlannedModFile> files,
        List<ResolvedMod> optionalDependencies,
        List<ModConflict> conflicts,
        List<String> warnings,
        long totalDownloadSize,
        boolean requiresConfirmation
) {
    public ModInstallationPlan {
        files = files == null ? List.of() : List.copyOf(files);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean installable() {
        return conflicts.isEmpty();
    }
}
