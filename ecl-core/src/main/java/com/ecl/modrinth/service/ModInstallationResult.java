package com.ecl.modrinth.service;

import com.ecl.modrinth.model.InstalledMod;

import java.util.List;

public record ModInstallationResult(List<InstalledMod> installedMods, boolean updated) {
    public ModInstallationResult {
        installedMods = installedMods == null ? List.of() : List.copyOf(installedMods);
    }
}
