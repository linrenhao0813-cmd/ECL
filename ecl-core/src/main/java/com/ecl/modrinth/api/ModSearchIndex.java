package com.ecl.modrinth.api;

import java.util.Locale;

public enum ModSearchIndex {
    RELEVANCE,
    DOWNLOADS,
    FOLLOWS,
    NEWEST,
    UPDATED;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
