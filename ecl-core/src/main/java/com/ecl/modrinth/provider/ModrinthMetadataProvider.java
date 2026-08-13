package com.ecl.modrinth.provider;

import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ModrinthMetadataProvider implements ModMetadataProvider {
    private final ModrinthApiClient client;
    private final boolean closeClient;

    public ModrinthMetadataProvider(ModrinthApiClient client) {
        this(client, true);
    }

    public ModrinthMetadataProvider(ModrinthApiClient client, boolean closeClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.closeClient = closeClient;
    }

    @Override
    public ContentSource source() {
        return ContentSource.MODRINTH;
    }

    @Override
    public CompletableFuture<ModSearchResult> search(ModSearchQuery query) {
        return client.searchMods(query);
    }

    @Override
    public CompletableFuture<ModProject> getProject(String idOrSlug) {
        return client.getProject(idOrSlug);
    }

    @Override
    public CompletableFuture<ModVersion> getVersion(String versionId) {
        return client.getVersion(versionId);
    }

    @Override
    public CompletableFuture<List<ModVersion>> getVersions(String projectId, String minecraftVersion,
                                                            String loader) {
        return client.getProjectVersions(projectId, minecraftVersion, loader);
    }

    @Override
    public CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
            Collection<String> hashes, String algorithm) {
        return client.getVersionsFromHashes(hashes, algorithm);
    }

    @Override
    public CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
            Collection<String> hashes, String algorithm, List<String> loaders,
            List<String> gameVersions) {
        return client.getLatestVersionsFromHashes(hashes, algorithm, loaders, gameVersions);
    }

    @Override
    public void close() {
        if (closeClient) {
            client.close();
        }
    }
}
