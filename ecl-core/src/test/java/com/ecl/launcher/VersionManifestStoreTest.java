package com.ecl.launcher;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionManifestStoreTest {
    @TempDir
    Path temp;

    @Test
    void freshCacheSkipsNetworkRefresh() throws Exception {
        File cache = temp.resolve("version_manifest.json").toFile();
        JsonObject cached = manifest("cached");
        HttpUtil.writeJson(cache, cached);
        AtomicInteger networkCalls = new AtomicInteger();
        long now = cache.lastModified() + 1_000;
        VersionManifestStore store = new VersionManifestStore(cache, () -> {
            networkCalls.incrementAndGet();
            return manifest("remote");
        }, () -> now, 300_000);

        JsonObject result = store.refresh();

        assertEquals("cached", result.get("id").getAsString());
        assertEquals(0, networkCalls.get());
    }

    @Test
    void staleCacheIsReplacedFromNetwork() throws Exception {
        File cache = temp.resolve("version_manifest.json").toFile();
        HttpUtil.writeJson(cache, manifest("stale"));
        AtomicInteger networkCalls = new AtomicInteger();
        long now = cache.lastModified() + 300_001;
        VersionManifestStore store = new VersionManifestStore(cache, () -> {
            networkCalls.incrementAndGet();
            return manifest("remote");
        }, () -> now, 300_000);

        JsonObject result = store.refresh();

        assertEquals("remote", result.get("id").getAsString());
        assertEquals("remote", store.loadCached().get("id").getAsString());
        assertEquals(1, networkCalls.get());
    }

    private static JsonObject manifest(String id) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        return manifest;
    }
}
