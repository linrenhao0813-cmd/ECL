package com.ecl.download.provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

public final class DownloadProviderRegistry {
    private final List<DownloadProvider> providers = new ArrayList<>();

    public DownloadProviderRegistry() {
        register(new OfficialDownloadProvider());
        register(new MirrorDownloadProvider());
        ServiceLoader.load(DownloadProvider.class).forEach(this::register);
    }

    public synchronized void register(DownloadProvider provider) {
        if (provider == null) return;
        providers.removeIf(existing -> existing.id().equalsIgnoreCase(provider.id()));
        providers.add(provider);
        providers.sort(Comparator.comparingInt(DownloadProvider::priority));
    }

    public synchronized List<URI> candidates(URI original) {
        Set<URI> resolved = new LinkedHashSet<>();
        providers.stream().filter(provider -> provider.supports(original))
                .forEach(provider -> resolved.addAll(provider.resolve(original)));
        if (resolved.isEmpty()) resolved.add(original);
        return List.copyOf(resolved);
    }

    public synchronized List<String> providerIds() {
        return providers.stream().map(DownloadProvider::id).toList();
    }
}
