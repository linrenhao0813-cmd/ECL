package com.ecl.modrinth.service;

import com.ecl.modrinth.model.ModCompatibility;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;

import java.util.List;
import java.util.Optional;

public interface ModVersionSelector {
    Optional<ModVersion> selectBestVersion(
            List<ModVersion> versions,
            ModCompatibility compatibility,
            ReleaseChannel channel
    );

    Optional<ModFile> selectInstallFile(ModVersion version);
}
