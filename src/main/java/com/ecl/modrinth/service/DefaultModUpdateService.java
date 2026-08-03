package com.ecl.modrinth.service;

import com.ecl.modrinth.api.ModInstallationException;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import com.ecl.modrinth.transaction.ModInstallationPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class DefaultModUpdateService implements ModUpdateService {
    private final ModrinthApiClient apiClient;
    private final ModVersionSelector selector;
    private final ModDependencyResolver dependencyResolver;
    private final InstallationPlanBuilder planBuilder;
    private final ModInstallationService installationService;
    private final Function<UUID, ModInstanceContext> instanceResolver;

    public DefaultModUpdateService(
            ModrinthApiClient apiClient,
            ModVersionSelector selector,
            ModDependencyResolver dependencyResolver,
            InstallationPlanBuilder planBuilder,
            ModInstallationService installationService,
            Function<UUID, ModInstanceContext> instanceResolver
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.dependencyResolver = Objects.requireNonNull(dependencyResolver, "dependencyResolver");
        this.planBuilder = Objects.requireNonNull(planBuilder, "planBuilder");
        this.installationService = Objects.requireNonNull(installationService, "installationService");
        this.instanceResolver = Objects.requireNonNull(instanceResolver, "instanceResolver");
    }

    @Override
    public CompletableFuture<List<ModUpdate>> checkUpdates(
            ModInstanceContext instance,
            Collection<InstalledMod> installedMods,
            ReleaseChannel channel
    ) {
        List<InstalledMod> candidates = installedMods == null ? List.of()
                : installedMods.stream()
                .filter(mod -> mod.sha1() != null && !mod.sha1().isBlank())
                .filter(mod -> mod.projectId() != null && !mod.projectId().startsWith("local:"))
                .toList();
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<String> hashes = candidates.stream().map(InstalledMod::sha1).distinct().toList();
        return apiClient.getLatestVersionsFromHashes(
                        hashes, "sha1", List.of(instance.loaderName()),
                        List.of(instance.minecraftVersion()))
                .thenApply(latest -> buildUpdates(candidates, latest, channel));
    }

    private List<ModUpdate> buildUpdates(
            List<InstalledMod> installed,
            Map<String, ModVersion> latest,
            ReleaseChannel channel
    ) {
        ReleaseChannel effectiveChannel = channel == null ? ReleaseChannel.RELEASE_ONLY : channel;
        List<ModUpdate> updates = new ArrayList<>();
        for (InstalledMod current : installed) {
            ModVersion version = latest.get(current.sha1());
            if (version == null || version.id().equals(current.versionId())
                    || !effectiveChannel.allows(version.versionType())) {
                continue;
            }
            ModFile file = selector.selectInstallFile(version).orElse(null);
            if (file != null) {
                updates.add(new ModUpdate(current, version, file, effectiveChannel));
            }
        }
        return List.copyOf(updates);
    }

    @Override
    public CompletableFuture<ModInstallationResult> applyUpdate(ModUpdate update) {
        Objects.requireNonNull(update, "update");
        ModInstanceContext instance = instanceResolver.apply(update.installedMod().instanceId());
        if (instance == null) {
            return CompletableFuture.failedFuture(
                    new ModInstallationException("找不到更新对应的游戏实例"));
        }
        return dependencyResolver.resolve(
                        instance, update.availableVersion(), java.util.Set.of(), update.releaseChannel())
                .thenApply(resolution -> planBuilder.build(instance, update.availableVersion(), resolution))
                .thenCompose(plan -> installationService.install(plan, null));
    }
}
