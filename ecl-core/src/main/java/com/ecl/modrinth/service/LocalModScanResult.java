package com.ecl.modrinth.service;

import com.ecl.modrinth.model.InstalledMod;

import java.util.List;

public record LocalModScanResult(
        List<InstalledMod> installedMods,
        List<LocalModScanItem> items,
        List<String> duplicateProjects,
        List<String> warnings
) {
    public LocalModScanResult {
        installedMods = installedMods == null ? List.of() : List.copyOf(installedMods);
        items = items == null ? List.of() : List.copyOf(items);
        duplicateProjects = duplicateProjects == null ? List.of() : List.copyOf(duplicateProjects);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
