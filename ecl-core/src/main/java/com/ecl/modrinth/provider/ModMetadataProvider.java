package com.ecl.modrinth.provider;

import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.util.FileUtil;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Online mod metadata extension point independent from the UI. */
public interface ModMetadataProvider extends AutoCloseable {
    ContentSource source();

    default String id() {
        return source().id();
    }

    CompletableFuture<ModSearchResult> search(ModSearchQuery query);

    CompletableFuture<ModProject> getProject(String idOrSlug);

    CompletableFuture<ModVersion> getVersion(String versionId);

    CompletableFuture<List<ModVersion>> getVersions(String projectId, String minecraftVersion,
                                                     String loader);

    CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
            Collection<String> hashes, String algorithm);

    CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
            Collection<String> hashes, String algorithm, List<String> loaders,
            List<String> gameVersions);

    default boolean supportsSha1HashLookup() {
        return true;
    }

    default boolean canCheckUpdates(InstalledMod installedMod) {
        return installedMod != null;
    }

    /** Recognizes local files while allowing providers to use source-specific fingerprints. */
    default CompletableFuture<Map<Path, ModVersion>> getVersionsByFiles(Collection<Path> files) {
        List<Path> paths = files == null ? List.of() : files.stream().distinct().toList();
        if (paths.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            Map<Path, String> hashes = new LinkedHashMap<>();
            for (Path path : paths) {
                try {
                    hashes.put(path, FileUtil.sha1(path.toFile()));
                } catch (java.io.IOException error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }
            return hashes;
        }).thenCompose(hashes -> getVersionsFromHashes(
                        new LinkedHashSet<>(hashes.values()), "sha1")
                .thenApply(versions -> {
                    Map<Path, ModVersion> result = new LinkedHashMap<>();
                    hashes.forEach((path, hash) -> {
                        ModVersion version = versions.get(hash);
                        if (version != null) {
                            result.put(path, version);
                        }
                    });
                    return Map.copyOf(result);
                }));
    }

    default CompletableFuture<List<ModProject>> resolveDependencies(ModVersion version) {
        if (version == null || version.dependencies().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        LinkedHashSet<String> projectIds = new LinkedHashSet<>();
        for (ModDependency dependency : version.dependencies()) {
            if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
                projectIds.add(dependency.projectId());
            }
        }
        CompletableFuture<?>[] requests = projectIds.stream()
                .map(this::getProject).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(requests).thenApply(ignored ->
                java.util.Arrays.stream(requests)
                        .map(request -> (ModProject) request.join()).toList());
    }

    default CompletableFuture<String> getChangelog(String projectId, String versionId) {
        return getVersion(versionId).thenApply(version -> version.changelog() == null
                ? "" : version.changelog());
    }

    default CompletableFuture<Optional<ModVersion>> getVersionByFile(Path file) {
        return getVersionsByFiles(List.of(file))
                .thenApply(versions -> Optional.ofNullable(versions.get(file)));
    }

    @Override
    default void close() {
    }
}
