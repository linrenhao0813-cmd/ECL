package com.ecl.modrinth.provider;

import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.util.FileUtil;

import java.nio.file.Path;
import java.util.Collection;
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                return FileUtil.sha1(file.toFile());
            } catch (java.io.IOException error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).thenCompose(hash -> getVersionsFromHashes(List.of(hash), "sha1")
                .thenApply(versions -> Optional.ofNullable(versions.get(hash))));
    }

    @Override
    default void close() {
    }
}
