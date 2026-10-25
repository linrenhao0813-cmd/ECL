package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.transaction.ModInstallationPlan;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface ModUpdateService {
    default CompletableFuture<ModInstallationResult> applyPreparedUpdate(
            PreparedModUpdate prepared, com.ecl.operation.InstanceOperationSession session) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Update sessions are not supported"));
    }

    CompletableFuture<List<ModUpdate>> checkUpdates(
            ModInstanceContext instance,
            Collection<InstalledMod> installedMods,
            ReleaseChannel channel
    );

    CompletableFuture<ModInstallationResult> applyUpdate(ModUpdate update);

    /** Resolves dependency versions and target files without installing anything. */
    default CompletableFuture<PreparedModUpdate> prepareUpdate(
            ModUpdate update, Set<String> protectedProjects) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Prepared updates are not supported"));
    }

    /** Applies the exact dependency and file plan returned by {@link #prepareUpdate}. */
    default CompletableFuture<ModInstallationResult> applyPreparedUpdate(PreparedModUpdate prepared) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Prepared updates are not supported"));
    }

    /** Protect skipped projects when an automatic update also resolves dependencies. */
    default CompletableFuture<ModInstallationResult> applyUpdate(ModUpdate update, Set<String> protectedProjects) {
        return applyUpdate(update);
    }

    record PreparedModUpdate(ModUpdate update, ModInstallationPlan installationPlan) {
        public PreparedModUpdate {
            java.util.Objects.requireNonNull(update, "update");
            java.util.Objects.requireNonNull(installationPlan, "installationPlan");
        }
    }
}
