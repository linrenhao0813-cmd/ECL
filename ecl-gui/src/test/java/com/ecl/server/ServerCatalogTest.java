package com.ecl.server;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCatalogTest {
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

    private static List<String> names(List<PublicServer> servers) {
        return servers.stream().map(PublicServer::name).toList();
    }

    private static PublicServer server(String host, int port) {
        return new PublicServer("Test", "smp", host, port, "1.21", "", "", "",
                List.of(), "T");
    }
}
