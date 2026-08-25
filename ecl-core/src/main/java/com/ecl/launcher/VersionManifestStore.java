package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Owns the official version manifest's network refresh and disk-cache fallback. */
final class VersionManifestStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionManifestStore.class);
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private final File cache;
    private final ManifestLoader remoteLoader;
    private final LongSupplier clock;
    private final long cacheTtlMillis;

    VersionManifestStore() {
        this(new File(ECLConfig.getVersionsDir(), "version_manifest.json"),
                () -> HttpUtil.getJson(ECLConfig.MC_VERSION_MANIFEST_URL),
                System::currentTimeMillis, CACHE_TTL_MILLIS);
    }

    VersionManifestStore(File cache, ManifestLoader remoteLoader,
                         LongSupplier clock, long cacheTtlMillis) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.remoteLoader = Objects.requireNonNull(remoteLoader, "remoteLoader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cacheTtlMillis = Math.max(0, cacheTtlMillis);
    }

    JsonObject refresh() throws IOException {
        JsonObject freshCache = loadFreshCached();
        if (freshCache != null) {
            return freshCache;
        }
        try {
            // Version metadata is an execution trust root. Never accept mirror-authored JSON;
            // mirrors remain available later for hash-bound binary artifacts.
            JsonObject manifest = remoteLoader.load();
            HttpUtil.writeJson(cache, manifest);
            return manifest;
        } catch (IOException networkError) {
            JsonObject cached = loadCached();
            if (cached == null) {
                throw networkError;
            }
            return cached;
        }
    }

    JsonObject loadCached() {
        if (!cache.exists()) {
            return null;
        }
        try {
            return HttpUtil.readJson(cache);
        } catch (IOException e) {
            LOGGER.warn("Failed to read cached version manifest from {}", cache, e);
            return null;
        }
    }

    private JsonObject loadFreshCached() {
        if (!cache.isFile()) {
            return null;
        }
        long ageMillis = Math.max(0, clock.getAsLong() - cache.lastModified());
        return ageMillis < cacheTtlMillis ? loadCached() : null;
    }

    @FunctionalInterface
    interface ManifestLoader {
        JsonObject load() throws IOException;
    }
}
