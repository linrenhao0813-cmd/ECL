package com.ecl.modrinth.provider;

import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Online mod metadata extension point independent from the UI. */
public interface ModMetadataProvider extends AutoCloseable {
    String id();

    CompletableFuture<List<ModProject>> search(String query, String minecraftVersion,
                                                String loader, int limit);

    CompletableFuture<ModProject> project(String idOrSlug);

    CompletableFuture<List<ModVersion>> versions(String projectId, String minecraftVersion,
                                                  String loader);

    @Override
    default void close() {
    }
}
