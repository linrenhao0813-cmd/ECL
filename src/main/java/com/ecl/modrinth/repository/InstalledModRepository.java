package com.ecl.modrinth.repository;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InstalledModRepository {
    List<InstalledMod> findAll(ModInstanceContext instance) throws IOException;

    Optional<InstalledMod> findByProjectId(ModInstanceContext instance, String projectId) throws IOException;

    void saveAll(ModInstanceContext instance, Collection<InstalledMod> mods) throws IOException;

    Path createSnapshot(ModInstanceContext instance, Collection<InstalledMod> mods, Path stagingDirectory)
            throws IOException;

    Path indexPath(ModInstanceContext instance);
}
