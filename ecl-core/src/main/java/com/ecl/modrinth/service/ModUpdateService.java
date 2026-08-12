package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ModUpdateService {
    CompletableFuture<List<ModUpdate>> checkUpdates(
            ModInstanceContext instance,
            Collection<InstalledMod> installedMods,
            ReleaseChannel channel
    );

    CompletableFuture<ModInstallationResult> applyUpdate(ModUpdate update);
}
