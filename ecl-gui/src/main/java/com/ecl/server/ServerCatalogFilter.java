package com.ecl.server;

import com.ecl.util.Messages;

import java.util.List;
import java.util.Objects;

/** Holds the catalog, category selection, and search filtering state for the browser. */
final class ServerCatalogFilter {
    private final ServerCatalog bundledCatalog;
    private ServerCatalog catalog;
    private ServerCategory activeCategory = ServerCategory.ALL;
    private String directorySource = Messages.get("server.source.bundled");

    ServerCatalogFilter(ServerCatalog bundledCatalog) {
        this.bundledCatalog = Objects.requireNonNull(bundledCatalog, "bundledCatalog");
        this.catalog = bundledCatalog;
    }

    List<PublicServer> filter(String query) {
        return catalog.filter(activeCategory, query);
    }

    int count(ServerCategory category) {
        return catalog.filter(category, "").size();
    }

    ServerCategory activeCategory() {
        return activeCategory;
    }

    void selectCategory(ServerCategory category) {
        activeCategory = Objects.requireNonNull(category, "category");
    }

    List<PublicServer> servers() {
        return catalog.servers();
    }

    String directorySource() {
        return directorySource;
    }

    void applyDirectorySnapshot(ServerDirectoryService.DirectorySnapshot snapshot) {
        catalog = bundledCatalog.withDiscoveredServers(snapshot.servers());
        directorySource = snapshot.cached()
                ? Messages.get("server.source.cache")
                : "minecraft-java-servers.com";
    }
}
