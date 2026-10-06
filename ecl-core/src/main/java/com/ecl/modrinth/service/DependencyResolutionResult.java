package com.ecl.modrinth.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record DependencyResolutionResult(
        List<ResolvedMod> installOrder,
        List<ResolvedMod> optionalDependencies,
        List<ModConflict> conflicts,
        List<String> warnings,
        Map<String, Set<String>> requiredByProjects
) {
    public DependencyResolutionResult {
        installOrder = installOrder == null ? List.of() : List.copyOf(installOrder);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        requiredByProjects = requiredByProjects == null
                ? installOrder.stream().filter(mod -> mod.requiredByProjectId() != null && !mod.requiredByProjectId().isBlank())
                        .collect(Collectors.groupingBy(mod -> mod.version().projectId(),
                                Collectors.mapping(ResolvedMod::requiredByProjectId, Collectors.toSet())))
                : requiredByProjects;
        requiredByProjects = requiredByProjects.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    public DependencyResolutionResult(List<ResolvedMod> installOrder, List<ResolvedMod> optionalDependencies,
                                      List<ModConflict> conflicts, List<String> warnings) {
        this(installOrder, optionalDependencies, conflicts, warnings, null);
    }
}
