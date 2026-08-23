package com.ecl.modrinth.ui.viewmodel;

import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.provider.ModMetadataProvider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Loads project and dependency metadata while reusing in-flight project requests. */
final class ModDependencyBrowserLoader {
    private final Function<Throwable, String> errorFormatter;
    private final Map<String, CompletableFuture<ModProject>> projectRequests = new ConcurrentHashMap<>();
    private ModMetadataProvider metadataProvider;

    ModDependencyBrowserLoader(ModMetadataProvider metadataProvider,
                               Function<Throwable, String> errorFormatter) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
        this.errorFormatter = Objects.requireNonNull(errorFormatter, "errorFormatter");
    }

    void setMetadataProvider(ModMetadataProvider provider) {
        metadataProvider = Objects.requireNonNull(provider, "provider");
        projectRequests.clear();
    }

    CompletableFuture<ModProject> loadProject(String projectId) {
        return projectRequests.computeIfAbsent(projectId, key ->
                metadataProvider.getProject(key).whenComplete((value, error) -> {
                    if (error != null) {
                        projectRequests.remove(key);
                    }
                }));
    }

    CompletableFuture<List<ModBrowserViewModel.DependencyGroup>> loadDependencyGroups(
            ModVersion version) {
        if (version == null || version.dependencies().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<ModBrowserViewModel.DependencyProject>> requests =
                version.dependencies().stream().map(this::loadDependencyProject).toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<DependencyType, List<ModBrowserViewModel.DependencyProject>> grouped =
                            new EnumMap<>(DependencyType.class);
                    requests.stream().map(CompletableFuture::join).forEach(item ->
                            grouped.computeIfAbsent(item.dependency().type(), key -> new ArrayList<>())
                                    .add(item));
                    List<ModBrowserViewModel.DependencyGroup> result = new ArrayList<>();
                    for (DependencyType type : List.of(DependencyType.REQUIRED,
                            DependencyType.OPTIONAL, DependencyType.EMBEDDED,
                            DependencyType.INCOMPATIBLE, DependencyType.UNKNOWN)) {
                        List<ModBrowserViewModel.DependencyProject> projects = grouped.get(type);
                        if (projects != null && !projects.isEmpty()) {
                            result.add(new ModBrowserViewModel.DependencyGroup(type,
                                    List.copyOf(projects)));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    private CompletableFuture<ModBrowserViewModel.DependencyProject> loadDependencyProject(
            ModDependency dependency) {
        CompletableFuture<ModProject> project;
        if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
            project = loadProject(dependency.projectId());
        } else if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            project = metadataProvider.getVersion(dependency.versionId())
                    .thenCompose(version -> loadProject(version.projectId()));
        } else {
            project = CompletableFuture.failedFuture(new IllegalArgumentException("依赖缺少项目标识"));
        }
        return project.handle((value, error) -> new ModBrowserViewModel.DependencyProject(
                dependency, value, error == null ? "" : errorFormatter.apply(error)));
    }
}
