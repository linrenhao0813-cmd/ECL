package com.ecl.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDirectoryServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsEveryPageAndClassifiesCommonGameModes() {
        Path cache = temporaryDirectory.resolve("servers.json");
        AtomicInteger calls = new AtomicInteger();
        ServerDirectoryService service = new ServerDirectoryService(cache, url -> {
            calls.incrementAndGet();
            if (url.endsWith("page=1")) {
                return response(1, 2,
                        server("Survival", "survival.test", "Survival"),
                        server("Combat", "pvp.test", "KitPvP"),
                        server("Automation", "tech.test", "Redstone"));
            }
            return response(2, 2,
                    server("Community", "smp.test", "SMP"),
                    server("Arcade", "games.test", "Minigames"));
        });

        ServerDirectoryService.DirectorySnapshot snapshot = service.load(true);

        assertEquals(2, calls.get());
        assertEquals(5, snapshot.servers().size());
        assertFalse(snapshot.cached());
        assertEquals(List.of(
                ServerCategory.SURVIVAL,
                ServerCategory.PVP,
                ServerCategory.TECH,
                ServerCategory.SMP,
                ServerCategory.ENTERTAINMENT),
                snapshot.servers().stream().map(PublicServer::categoryEnum).toList());
        assertEquals(ServerStatusState.ONLINE,
                snapshot.statuses().get("pvp.test").state());
    }

    @Test
    void reusesFreshCacheWithoutAnotherNetworkRequest() {
        Path cache = temporaryDirectory.resolve("servers.json");
        ServerDirectoryService writer = new ServerDirectoryService(cache,
                url -> response(1, 1, server("Cached", "cached.test", "SMP")));
        writer.load(true);

        AtomicInteger calls = new AtomicInteger();
        ServerDirectoryService reader = new ServerDirectoryService(cache, url -> {
            calls.incrementAndGet();
            throw new IllegalStateException("Network should not be used for a fresh cache");
        });

        ServerDirectoryService.DirectorySnapshot snapshot = reader.load(false);

        assertEquals(0, calls.get());
        assertTrue(snapshot.cached());
        assertEquals(List.of("cached.test"),
                snapshot.servers().stream().map(PublicServer::address).toList());
    }

    private static JsonObject response(int page, int totalPages, JsonObject... servers) {
        JsonObject response = new JsonObject();
        JsonArray data = new JsonArray();
        for (JsonObject server : servers) {
            data.add(server);
        }
        response.add("data", data);
        JsonObject meta = new JsonObject();
        meta.addProperty("page", page);
        meta.addProperty("total_pages", totalPages);
        response.add("meta", meta);
        return response;
    }

    private static JsonObject server(String name, String host, String type) {
        JsonObject server = new JsonObject();
        server.addProperty("name", name);
        server.addProperty("host", host);
        server.addProperty("port", 25565);
        server.addProperty("status", "online");
        server.addProperty("players_online", 42);
        server.addProperty("players_total", 100);
        server.addProperty("version", "1.21");
        JsonArray types = new JsonArray();
        types.add(type);
        server.add("types", types);
        return server;
    }
}
