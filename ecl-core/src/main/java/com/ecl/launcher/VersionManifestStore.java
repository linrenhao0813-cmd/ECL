package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/** Owns the official version manifest's network refresh and disk-cache fallback. */
final class VersionManifestStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionManifestStore.class);

    JsonObject refresh() throws IOException {
        File cache = cacheFile();
        try {
            // Version metadata is an execution trust root. Never accept mirror-authored JSON;
            // mirrors remain available later for hash-bound binary artifacts.
            JsonObject manifest = HttpUtil.getJson(ECLConfig.MC_VERSION_MANIFEST_URL);
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
        File cache = cacheFile();
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

    private File cacheFile() {
        return new File(ECLConfig.getVersionsDir(), "version_manifest.json");
    }
}
