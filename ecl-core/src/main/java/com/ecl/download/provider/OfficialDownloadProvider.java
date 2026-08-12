package com.ecl.download.provider;

import java.net.URI;
import java.util.List;

public final class OfficialDownloadProvider implements DownloadProvider {
    @Override
    public String id() {
        return "official";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean supports(URI original) {
        return original != null && ("http".equalsIgnoreCase(original.getScheme())
                || "https".equalsIgnoreCase(original.getScheme()));
    }

    @Override
    public List<URI> resolve(URI original) {
        return List.of(original);
    }
}
