package com.ecl.modrinth.api;

import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ModrinthApiClient extends AutoCloseable {
    CompletableFuture<ModSearchResult> searchMods(ModSearchQuery query);

    CompletableFuture<ModProject> getProject(String projectIdOrSlug);

    CompletableFuture<ModVersion> getVersion(String versionId);

    CompletableFuture<List<ModVersion>> getProjectVersions(
            String projectId,
            String minecraftVersion,
            String loader
    );

    CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
            Collection<String> hashes,
            String algorithm
    );

    CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
            Collection<String> hashes,
            String algorithm,
            List<String> loaders,
            List<String> gameVersions
    );

    @Override
    void close();
}
