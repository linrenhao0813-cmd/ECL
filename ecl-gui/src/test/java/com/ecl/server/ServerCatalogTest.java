package com.ecl.server;

import com.ecl.util.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCatalogTest {
    private Locale originalLocale;

    @BeforeEach
    void selectDeterministicLocale() {
        originalLocale = Messages.locale();
        Messages.setLocale(Locale.forLanguageTag("zh-CN"));
    }

    @AfterEach
    void restoreLocale() {
        Messages.setLocale(originalLocale);
    }

    @Test
    void bundledCatalogCoversEveryRequestedCategory() {
        ServerCatalog catalog = ServerCatalog.load();

        assertTrue(catalog.servers().size() >= 20);
        Set<ServerCategory> categories = EnumSet.noneOf(ServerCategory.class);
        catalog.servers().stream().map(PublicServer::categoryEnum).forEach(categories::add);
        assertEquals(EnumSet.of(
                ServerCategory.SURVIVAL,
                ServerCategory.SMP,
                ServerCategory.PVP,
                ServerCategory.TECH,
                ServerCategory.ENTERTAINMENT), categories);
    }

    @Test
    void searchMatchesNameAddressTagsAndLocalizedCategory() {
        ServerCatalog catalog = ServerCatalog.load();

        assertEquals(List.of("Hypixel"), names(catalog.filter(ServerCategory.ALL, "HYPIXEL")));
        assertFalse(catalog.filter(ServerCategory.ALL, "生电").isEmpty());
        assertTrue(catalog.filter(ServerCategory.ALL, "mc.hypixel.net").stream()
                .anyMatch(server -> server.name().equals("Hypixel")));
    }

    @Test
    void whitespaceSeparatedTermsMustAllMatch() {
        ServerCatalog catalog = ServerCatalog.load();

        List<PublicServer> matches = catalog.filter(ServerCategory.ALL, "pvp 国际");

        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().allMatch(server -> server.categoryEnum() == ServerCategory.PVP));
        assertTrue(matches.stream().allMatch(server -> server.region().contains("国际")));
    }

    @Test
    void categoryFilterAndSearchCompose() {
        ServerCatalog catalog = ServerCatalog.load();

        List<PublicServer> matches = catalog.filter(ServerCategory.SURVIVAL, "经济");

        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().allMatch(
                server -> server.categoryEnum() == ServerCategory.SURVIVAL));
    }

    @Test
    void addressOmitsOnlyTheDefaultPort() {
        PublicServer defaultPort = server("example.org", 25565);
        PublicServer customPort = server("example.org", 25566);

        assertEquals("example.org", defaultPort.address());
        assertEquals("example.org:25566", customPort.address());
    }

    @Test
    void websiteUriAllowsOnlyNormalWebLinks() {
        assertEquals("https://example.org/details",
                serverWithWebsite("https://example.org/details").websiteUri().toString());
        assertNull(serverWithWebsite("http://example.org").websiteUri());
        assertNull(serverWithWebsite("file:///C:/secrets.txt").websiteUri());
        assertNull(serverWithWebsite("javascript:alert(1)").websiteUri());
        assertNull(serverWithWebsite("https://user@example.org").websiteUri());
    }

    @Test
    void bundledDescriptionsRegionsAndTagsFollowTheSelectedLocale() {
        try {
            Messages.setLocale(Locale.ENGLISH);
            PublicServer english = named(ServerCatalog.load(), "Hypixel");
            assertTrue(english.description().startsWith("The world's largest"));
            assertEquals("International", english.region());
            assertTrue(english.tags().contains("Minigames"));

            Messages.setLocale(Locale.forLanguageTag("zh-TW"));
            PublicServer traditional = named(ServerCatalog.load(), "Hypixel");
            assertTrue(traditional.description().startsWith("全球規模最大"));
            assertEquals("國際", traditional.region());
            assertTrue(traditional.tags().contains("小遊戲"));
        } finally {
            Messages.setLocale(Locale.forLanguageTag("zh-CN"));
        }
    }

    private static List<String> names(List<PublicServer> servers) {
        return servers.stream().map(PublicServer::name).toList();
    }

    private static PublicServer named(ServerCatalog catalog, String name) {
        return catalog.servers().stream().filter(server -> server.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static PublicServer server(String host, int port) {
        return new PublicServer("Test", "smp", host, port, "1.21", "", "", "",
                List.of(), "T");
    }

    private static PublicServer serverWithWebsite(String website) {
        return new PublicServer("Test", "smp", "example.org", 25565, "1.21", "", "",
                website, List.of(), "T");
    }
}
