package com.ecl.modrinth.service;

import com.ecl.modrinth.model.InstalledMod;

import java.nio.file.Path;

public record LocalModScanItem(
        Path file,
        InstalledMod installedMod,
        boolean recognized,
        boolean damaged,
        String message
) {
}
