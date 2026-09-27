package com.ecl.modrinth.provider;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchQuery;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModMetadataProviderRegistryTest {
    @Test
    void resolvesRegisteredProviderByNormalizedId() {
        ModrinthMetadataProvider provider = new ModrinthMetadataProvider(
                new TestFixtures.FakeApi(), false);
        try (ModMetadataProviderRegistry registry = new ModMetadataProviderRegistry(provider)) {
            assertSame(provider, registry.require("modrinth"));
            assertEquals("modrinth", provider.id());
            assertEquals("Modrinth", provider.displayName());
        }
    }

    @Test
    void adapterPreservesSearchPagingContract() {
        ModrinthMetadataProvider provider = new ModrinthMetadataProvider(
                new TestFixtures.FakeApi(), false);
        ModSearchQuery query = new ModSearchQuery("sodium", "1.21.1", "fabric",
                Set.of("optimization"), ModSearchIndex.DOWNLOADS, 40, 20);

        assertEquals(20, provider.search(query).join().totalHits());
    }
}
