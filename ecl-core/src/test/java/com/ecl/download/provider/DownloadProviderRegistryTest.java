package com.ecl.download.provider;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadProviderRegistryTest {
    @Test
    void keepsOfficialFirstAndAddsKnownMirrors() {
        DownloadProviderRegistry registry = new DownloadProviderRegistry();
        URI original = URI.create("https://libraries.minecraft.net/a/b.jar");

        var candidates = registry.candidates(original);

        assertEquals(original, candidates.get(0));
        assertTrue(candidates.stream().anyMatch(uri -> uri.getHost().contains("bmclapi")));
        assertTrue(registry.providerIds().contains("official"));
    }
}
