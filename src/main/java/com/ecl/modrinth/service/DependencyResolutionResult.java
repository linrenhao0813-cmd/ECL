package com.ecl.modrinth.service;

import java.util.List;

public record DependencyResolutionResult(
        List<ResolvedMod> installOrder,
        List<ResolvedMod> optionalDependencies,
        List<ModConflict> conflicts,
        List<String> warnings
) {
    public DependencyResolutionResult {
        installOrder = installOrder == null ? List.of() : List.copyOf(installOrder);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
