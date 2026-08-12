package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ModManagementService {
    CompletableFuture<List<InstalledMod>> list(ModInstanceContext instance);

    CompletableFuture<List<InstalledMod>> setEnabled(
            ModInstanceContext instance,
            Collection<String> projectIds,
            boolean enabled
    );

    CompletableFuture<List<InstalledMod>> uninstall(
            ModInstanceContext instance,
            Collection<String> projectIds
    );

    CompletableFuture<InstalledMod> importLocalJar(ModInstanceContext instance, Path jarFile);
}
