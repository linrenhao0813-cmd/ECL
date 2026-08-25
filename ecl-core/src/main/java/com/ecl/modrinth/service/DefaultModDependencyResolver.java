package com.ecl.modrinth.service;

import com.ecl.modrinth.api.DependencyResolutionException;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.api.NoCompatibleVersionException;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModCompatibility;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.provider.ModrinthMetadataProvider;
import com.ecl.util.FileUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class DefaultModDependencyResolver implements ModDependencyResolver {
    private final ModMetadataProvider metadataProvider;
    private final ModVersionSelector versionSelector;
    private final Function<ModInstanceContext, Collection<InstalledMod>> installedModsProvider;
    private final int maxDepth;
    private final int maxDependencies;

    public DefaultModDependencyResolver(ModrinthApiClient apiClient, ModVersionSelector versionSelector) {
        this(new ModrinthMetadataProvider(apiClient, false), versionSelector,
                ignored -> List.of(), 32, 256);
    }

    public DefaultModDependencyResolver(ModMetadataProvider metadataProvider,
                                        ModVersionSelector versionSelector) {
        this(metadataProvider, versionSelector, ignored -> List.of(), 32, 256);
    }

    public DefaultModDependencyResolver(
            ModrinthApiClient apiClient,
            ModVersionSelector versionSelector,
            Function<ModInstanceContext, Collection<InstalledMod>> installedModsProvider,
            int maxDepth,
            int maxDependencies
    ) {
        this(new ModrinthMetadataProvider(apiClient, false), versionSelector,
                installedModsProvider, maxDepth, maxDependencies);
    }

    public DefaultModDependencyResolver(
            ModMetadataProvider metadataProvider,
            ModVersionSelector versionSelector,
            Function<ModInstanceContext, Collection<InstalledMod>> installedModsProvider,
            int maxDepth,
            int maxDependencies
    ) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
        this.versionSelector = Objects.requireNonNull(versionSelector, "versionSelector");
        this.installedModsProvider = Objects.requireNonNull(installedModsProvider, "installedModsProvider");
        if (maxDepth < 1 || maxDependencies < 1) {
            throw new IllegalArgumentException("Dependency limits must be positive");
        }
        this.maxDepth = maxDepth;
        this.maxDependencies = maxDependencies;
    }

    @Override
    public CompletableFuture<DependencyResolutionResult> resolve(ModInstanceContext instance, ModVersion rootVersion) {
        Objects.requireNonNull(rootVersion, "rootVersion");
        return resolve(instance, rootVersion, Set.of(),
                ReleaseChannel.forVersionType(rootVersion.versionType()));
    }

    @Override
    public CompletableFuture<DependencyResolutionResult> resolve(
            ModInstanceContext instance,
            ModVersion rootVersion,
            Set<String> selectedOptionalProjectIds) {
        Objects.requireNonNull(rootVersion, "rootVersion");
        return resolve(instance, rootVersion, selectedOptionalProjectIds,
                ReleaseChannel.forVersionType(rootVersion.versionType()));
    }

    @Override
    public CompletableFuture<DependencyResolutionResult> resolve(
            ModInstanceContext instance,
            ModVersion rootVersion,
            Set<String> selectedOptionalProjectIds,
            ReleaseChannel releaseChannel) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(rootVersion, "rootVersion");
        if (!instance.loader().supportsMods()) {
            return CompletableFuture.failedFuture(
                    new DependencyResolutionException("当前实例没有可用的模组加载器"));
        }
        ModCompatibility compatibility = new ModCompatibility(instance.minecraftVersion(), instance.loader());
        ReleaseChannel effectiveChannel = releaseChannel == null
                ? ReleaseChannel.forVersionType(rootVersion.versionType()) : releaseChannel;
        if (versionSelector.selectBestVersion(
                List.of(rootVersion), compatibility, effectiveChannel).isEmpty()) {
            return CompletableFuture.failedFuture(new NoCompatibleVersionException(
                    "目标模组不兼容 " + compatibility.minecraftVersion() + " / "
                            + compatibility.loader().apiName() + " / " + effectiveChannel));
        }
        ModFile rootFile = versionSelector.selectInstallFile(rootVersion)
                .orElseThrow(() -> new NoCompatibleVersionException("目标模组版本没有可安装文件"));

        State state = new State(instance, compatibility, installedModsProvider.apply(instance),
                selectedOptionalProjectIds == null ? Set.of() : Set.copyOf(selectedOptionalProjectIds),
                effectiveChannel);
        return visit(state, rootVersion, rootFile, false, "", new ArrayDeque<>(), 0)
                .thenApply(ignored -> new DependencyResolutionResult(
                        state.installOrder,
                        state.optionalDependencies,
                        state.conflicts,
                        state.warnings));
    }

    private CompletableFuture<Void> visit(State state, ModVersion version, ModFile file, boolean dependency,
                                          String requiredBy, Deque<String> path, int depth) {
        if (depth > maxDepth) {
            return CompletableFuture.failedFuture(new DependencyResolutionException(
                    "依赖深度超过限制 " + maxDepth + ": " + String.join(" -> ", path)));
        }
        String projectId = projectIdentity(version);
        if (path.contains(projectId)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(projectId);
            return CompletableFuture.failedFuture(new DependencyResolutionException(
                    "检测到循环依赖: " + String.join(" -> ", cycle)));
        }
        ModVersion selected = state.selectedVersions.get(projectId);
        if (selected != null) {
            if (!selected.id().equals(version.id())) {
                state.conflicts.add(new ModConflict(projectId, projectId,
                        "同一项目要求不同版本: " + selected.id() + " / " + version.id(),
                        appendPath(path, projectId)));
            }
            return CompletableFuture.completedFuture(null);
        }
        if (++state.dependencyCount > maxDependencies) {
            return CompletableFuture.failedFuture(new DependencyResolutionException(
                    "依赖数量超过限制 " + maxDependencies));
        }

        state.selectedVersions.put(projectId, version);
        path.addLast(projectId);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ModDependency modDependency : version.dependencies()) {
            chain = chain.thenCompose(ignored ->
                    handleDependency(state, version, modDependency, path, depth + 1));
        }
        return chain.thenRun(() -> state.installOrder.add(new ResolvedMod(
                        version, file, dependency, requiredBy, List.copyOf(path))))
                .whenComplete((ignored, error) -> path.removeLast());
    }

    private CompletableFuture<Void> handleDependency(State state, ModVersion owner, ModDependency dependency,
                                                     Deque<String> path, int depth) {
        if (dependency == null || dependency.type() == DependencyType.UNKNOWN) {
            state.warnings.add("忽略未知依赖类型: " + owner.projectId());
            return CompletableFuture.completedFuture(null);
        }
        return switch (dependency.type()) {
            case REQUIRED -> {
                if (hasInstalledDependencyFile(state, dependency)) {
                    yield CompletableFuture.completedFuture(null);
                }
                yield resolveDependencyVersion(state, dependency, path)
                        .thenCompose(version -> {
                            ModFile file = versionSelector.selectInstallFile(version)
                                    .orElseThrow(() -> missingDependency(path, dependency, "没有可安装文件"));
                            return visit(state, version, file, true, projectIdentity(owner), path, depth);
                        });
            }
            case OPTIONAL -> resolveDependencyVersion(state, dependency, path)
                    .thenCompose(version -> {
                        ModFile file = versionSelector.selectInstallFile(version)
                                .orElseThrow(() -> missingDependency(path, dependency, "没有可安装文件"));
                        if (state.selectedOptionalProjects.contains(projectIdentity(version))) {
                            return visit(state, version, file, true, projectIdentity(owner), path, depth);
                        }
                        state.optionalDependencies.add(new ResolvedMod(
                                version, file, true, projectIdentity(owner),
                                appendPath(path, projectIdentity(version))));
                        return CompletableFuture.completedFuture(null);
                    }).exceptionally(error -> {
                        if (state.selectedOptionalProjects.contains(dependencyIdentity(dependency))) {
                            throw new DependencyResolutionException(
                                    "所选可选依赖无法解析: " + dependencyIdentity(dependency), unwrap(error));
                        }
                        state.warnings.add("可选依赖不可用，已跳过: " + dependencyIdentity(dependency));
                        return null;
                    });
            case INCOMPATIBLE -> {
                detectIncompatible(state, owner, dependency, path);
                yield CompletableFuture.completedFuture(null);
            }
            case EMBEDDED -> {
                state.warnings.add("内嵌依赖无需单独下载: " + dependencyIdentity(dependency));
                yield CompletableFuture.completedFuture(null);
            }
            case UNKNOWN -> CompletableFuture.completedFuture(null);
        };
    }

    private static boolean hasInstalledDependencyFile(State state, ModDependency dependency) {
        String requiredVersionId = text(dependency.versionId());
        String requiredProjectId = text(dependency.projectId());
        if (requiredVersionId.isBlank() && requiredProjectId.isBlank()) {
            return false;
        }

        Collection<InstalledMod> candidates = requiredVersionId.isBlank()
                ? state.installedByProjectId.getOrDefault(requiredProjectId, List.of())
                : state.installedByVersionId.getOrDefault(requiredVersionId, List.of());
        return candidates.stream().anyMatch(state::isUsableInstalledFile);
    }

    private static boolean isUsableInstalledFile(
            InstalledMod mod,
            Path gameDirectory,
            Path modsDirectory
    ) {
        Path path = gameDirectory.resolve(mod.relativePath()).normalize();
        if (!path.startsWith(modsDirectory) || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            if (mod.fileSize() > 0 && Files.size(path) != mod.fileSize()) {
                return false;
            }
        } catch (java.io.IOException e) {
            return false;
        }
        return mod.sha1().isBlank() || FileUtil.verifySha1(path.toFile(), mod.sha1());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private CompletableFuture<ModVersion> resolveDependencyVersion(State state, ModDependency dependency,
                                                                   Deque<String> path) {
        if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            return metadataProvider.getVersion(dependency.versionId()).thenApply(version -> {
                if (versionSelector.selectBestVersion(
                        List.of(version), state.compatibility, state.releaseChannel).isEmpty()) {
                    throw missingDependency(path, dependency,
                            "指定版本与当前实例或发布通道不兼容");
                }
                return version;
            });
        }
        if (dependency.projectId() == null || dependency.projectId().isBlank()) {
            return CompletableFuture.failedFuture(missingDependency(path, dependency, "缺少项目 ID 和版本 ID"));
        }
        return metadataProvider.getVersions(
                        dependency.projectId(),
                        state.compatibility.minecraftVersion(),
                        state.compatibility.loader().apiName())
                .thenApply(versions -> versionSelector.selectBestVersion(
                                versions, state.compatibility, state.releaseChannel)
                        .orElseThrow(() -> missingDependency(path, dependency,
                                "没有兼容 " + state.compatibility.minecraftVersion() + " / "
                                        + state.compatibility.loader().apiName() + " / "
                                        + state.releaseChannel + " 的版本")));
    }

    private void detectIncompatible(State state, ModVersion owner, ModDependency dependency,
                                    Deque<String> path) {
        String targetProject = dependency.projectId();
        String targetVersion = dependency.versionId();
        boolean planned = targetProject != null && !targetProject.isBlank()
                && state.selectedVersions.containsKey(targetProject);
        boolean installed = state.installedMods.stream().anyMatch(mod ->
                (targetProject != null && !targetProject.isBlank() && targetProject.equals(mod.projectId()))
                        || (targetVersion != null && !targetVersion.isBlank() && targetVersion.equals(mod.versionId())));
        if (planned || installed) {
            state.conflicts.add(new ModConflict(
                    projectIdentity(owner),
                    dependencyIdentity(dependency),
                    "存在 incompatible 冲突",
                    appendPath(path, dependencyIdentity(dependency))));
        }
    }

    private static NoCompatibleVersionException missingDependency(
            Deque<String> path, ModDependency dependency, String reason) {
        return new NoCompatibleVersionException("无法解析依赖 "
                + String.join(" -> ", appendPath(path, dependencyIdentity(dependency)))
                + ": " + reason);
    }

    private static String dependencyIdentity(ModDependency dependency) {
        if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
            return dependency.projectId();
        }
        if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            return dependency.versionId();
        }
        return "<unknown>";
    }

    private static String projectIdentity(ModVersion version) {
        return version.projectId() == null || version.projectId().isBlank()
                ? "version:" + version.id() : version.projectId();
    }

    private static List<String> appendPath(Deque<String> path, String value) {
        List<String> result = new ArrayList<>(path);
        result.add(value);
        return result;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class State {
        private final ModInstanceContext instance;
        private final ModCompatibility compatibility;
        private final Collection<InstalledMod> installedMods;
        private final Map<String, List<InstalledMod>> installedByProjectId;
        private final Map<String, List<InstalledMod>> installedByVersionId;
        private final Map<Path, Boolean> installedFileValidity = new HashMap<>();
        private final Set<String> selectedOptionalProjects;
        private final ReleaseChannel releaseChannel;
        private final Map<String, ModVersion> selectedVersions = new LinkedHashMap<>();
        private final List<ResolvedMod> installOrder = new ArrayList<>();
        private final List<ResolvedMod> optionalDependencies = new ArrayList<>();
        private final List<ModConflict> conflicts = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int dependencyCount;

        private State(ModInstanceContext instance, ModCompatibility compatibility,
                      Collection<InstalledMod> installedMods,
                      Set<String> selectedOptionalProjects, ReleaseChannel releaseChannel) {
            this.instance = instance;
            this.compatibility = compatibility;
            this.installedMods = installedMods == null ? List.of() : List.copyOf(installedMods);
            this.installedByProjectId = indexInstalledMods(this.installedMods, InstalledMod::projectId);
            this.installedByVersionId = indexInstalledMods(this.installedMods, InstalledMod::versionId);
            this.selectedOptionalProjects = selectedOptionalProjects;
            this.releaseChannel = releaseChannel;
        }

        private boolean isUsableInstalledFile(InstalledMod mod) {
            Path path = instance.gameDirectory().toAbsolutePath().normalize()
                    .resolve(mod.relativePath()).normalize();
            return installedFileValidity.computeIfAbsent(path, ignored ->
                    DefaultModDependencyResolver.isUsableInstalledFile(
                            mod, instance.gameDirectory().toAbsolutePath().normalize(),
                            instance.modsDirectory().toAbsolutePath().normalize()));
        }

        private Map<String, List<InstalledMod>> indexInstalledMods(
                Collection<InstalledMod> mods,
                Function<InstalledMod, String> keyExtractor) {
            Map<String, List<InstalledMod>> index = new HashMap<>();
            for (InstalledMod mod : mods) {
                String key = text(keyExtractor.apply(mod));
                if (!key.isBlank()) {
                    index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mod);
                }
            }
            return index;
        }
    }
}
