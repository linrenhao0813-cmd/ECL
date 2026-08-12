package com.ecl.modrinth.model;

public record ModUpdate(
        InstalledMod installedMod,
        ModVersion availableVersion,
        ModFile selectedFile,
        ReleaseChannel releaseChannel
) {
    public ModUpdate {
        if (releaseChannel == null) {
            releaseChannel = ReleaseChannel.forVersionType(
                    availableVersion == null ? null : availableVersion.versionType());
        }
    }

    public ModUpdate(InstalledMod installedMod, ModVersion availableVersion, ModFile selectedFile) {
        this(installedMod, availableVersion, selectedFile, null);
    }
}
