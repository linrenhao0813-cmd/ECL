package com.ecl.modrinth.api;

import com.ecl.modrinth.model.ModProject;

import java.util.List;

public record ModSearchResult(List<ModProject> hits, int offset, int limit, int totalHits) {
    public ModSearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
