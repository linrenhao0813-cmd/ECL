package com.ecl.modrinth.transaction;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.service.ModConflict;
import com.ecl.modrinth.service.ResolvedMod;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ModInstallationPlan(
        ModInstanceContext instance,
        ModVersion rootVersion,
        List<PlannedModFile> files,
        List<ResolvedMod> optionalDependencies,
        List<ModConflict> conflicts,
        List<String> warnings,
        long totalDownloadSize,
        boolean requiresConfirmation,
        Map<String, Set<String>> requiredByProjects
) {
    public ModInstallationPlan {
        files = files == null ? List.of() : List.copyOf(files);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        requiredByProjects = requiredByProjects == null
                ? files.stream().filter(file -> file.requiredByProjectId() != null && !file.requiredByProjectId().isBlank())
                        .collect(Collectors.groupingBy(file -> file.version().projectId(),
                                Collectors.mapping(PlannedModFile::requiredByProjectId, Collectors.toSet())))
                : requiredByProjects;
        requiredByProjects = requiredByProjects.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    public ModInstallationPlan(ModInstanceContext instance, ModVersion rootVersion, List<PlannedModFile> files,
                               List<ResolvedMod> optionalDependencies, List<ModConflict> conflicts, List<String> warnings,
                               long totalDownloadSize, boolean requiresConfirmation) {
        this(instance, rootVersion, files, optionalDependencies, conflicts, warnings,
                totalDownloadSize, requiresConfirmation, null);
    }

    public boolean installable() {
        return conflicts.isEmpty();
    }
}
