package com.ecl.modrinth.provider;

import com.ecl.modrinth.api.DefaultModrinthApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class ModMetadataProviderRegistry implements AutoCloseable {
    private final List<ModMetadataProvider> providers = new ArrayList<>();

    public ModMetadataProviderRegistry() {
        register(new ModrinthMetadataProvider(new DefaultModrinthApiClient()));
        ServiceLoader.load(ModMetadataProvider.class).forEach(this::register);
    }

    public ModMetadataProviderRegistry(ModMetadataProvider... initialProviders) {
        if (initialProviders != null) {
            for (ModMetadataProvider provider : initialProviders) {
                register(provider);
            }
        }
        ServiceLoader.load(ModMetadataProvider.class).forEach(this::register);
    }

    public synchronized void register(ModMetadataProvider provider) {
        if (provider == null) return;
        providers.removeIf(existing -> existing.source() == provider.source()
                || existing.id().equalsIgnoreCase(provider.id()));
        providers.add(provider);
    }

    public synchronized List<ModMetadataProvider> providers() {
        return List.copyOf(providers);
    }

    public synchronized ModMetadataProvider require(ContentSource source) {
        return providers.stream()
                .filter(provider -> provider.source() == source)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Content source is unavailable: " + source));
    }

    @Override
    public synchronized void close() {
        providers.forEach(ModMetadataProvider::close);
        providers.clear();
    }
}
