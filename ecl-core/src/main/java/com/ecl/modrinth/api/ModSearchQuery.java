package com.ecl.modrinth.api;

import java.util.Set;

public record ModSearchQuery(
        String keyword,
        String minecraftVersion,
        String loader,
        String projectType,
        Set<String> categories,
        ModSearchIndex index,
        int offset,
        int limit
) {
    /** Backwards-compatible constructor for mod searches. */
    public ModSearchQuery(String keyword, String minecraftVersion, String loader,
                          Set<String> categories, ModSearchIndex index, int offset, int limit) {
        this(keyword, minecraftVersion, loader, "mod", categories, index, offset, limit);
    }

    public ModSearchQuery {
        keyword = keyword == null ? "" : keyword.trim();
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        loader = loader == null ? "" : loader.trim();
        projectType = projectType == null || projectType.isBlank() ? "mod" : projectType.trim();
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        index = index == null ? ModSearchIndex.RELEVANCE : index;
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
