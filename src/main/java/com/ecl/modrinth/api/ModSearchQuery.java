package com.ecl.modrinth.api;

import java.util.Set;

public record ModSearchQuery(
        String keyword,
        String minecraftVersion,
        String loader,
        Set<String> categories,
        ModSearchIndex index,
        int offset,
        int limit
) {
    public ModSearchQuery {
        keyword = keyword == null ? "" : keyword.trim();
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        if (loader == null || loader.isBlank()) {
            throw new IllegalArgumentException("loader must not be blank");
        }
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
