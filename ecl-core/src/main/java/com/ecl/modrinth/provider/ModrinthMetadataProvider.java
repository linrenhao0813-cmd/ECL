package com.ecl.modrinth.provider;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ModrinthMetadataProvider implements ModMetadataProvider {
    private final ModrinthApiClient client;

    public ModrinthMetadataProvider(ModrinthApiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String id() {
        return "modrinth";
    }

    @Override
    public CompletableFuture<List<ModProject>> search(String query, String minecraftVersion,
                                                       String loader, int limit) {
        ModSearchQuery request = new ModSearchQuery(query, minecraftVersion, loader, Set.of(),
                ModSearchIndex.RELEVANCE, 0, Math.max(1, Math.min(100, limit)));
        return client.searchMods(request).thenApply(result -> result.hits());
    }

    @Override
    public CompletableFuture<ModProject> project(String idOrSlug) {
        return client.getProject(idOrSlug);
    }

    @Override
    public CompletableFuture<List<ModVersion>> versions(String projectId, String minecraftVersion,
                                                         String loader) {
        return client.getProjectVersions(projectId, minecraftVersion, loader);
    }

    @Override
    public void close() {
        client.close();
    }
}
