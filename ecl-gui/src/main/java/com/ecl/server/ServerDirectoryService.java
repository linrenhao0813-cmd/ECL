package com.ecl.server;

import com.ecl.ECLConfig;
import com.ecl.util.GsonProvider;
import com.ecl.util.HttpUtil;
import com.ecl.util.Messages;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Downloads and caches the public Minecraft Java server directory. */
public final class ServerDirectoryService {
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 5;
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final String ENDPOINT =
            "https://minecraft-java-servers.com/api/v1/servers?per_page="
                    + PAGE_SIZE + "&page=";

    private final Path cacheFile;
    private final Function<String, JsonObject> fetcher;

    public ServerDirectoryService() {
        this(ECLConfig.getBaseDir().toPath().resolve("cache/public-server-directory.json"),
                ServerDirectoryService::fetchJson);
    }

    ServerDirectoryService(Path cacheFile, Function<String, JsonObject> fetcher) {
        this.cacheFile = cacheFile;
        this.fetcher = fetcher;
    }

    /** Loads a fresh cache when possible, otherwise refreshes it from the public directory API. */
    public DirectorySnapshot load(boolean forceRefresh) {
        DirectorySnapshot cached = readCache();
        if (!forceRefresh && cached != null && isFresh(cached.fetchedAtEpochMillis())) {
            return cached;
        }
        try {
            DirectorySnapshot remote = fetchRemote();
            writeCache(remote);
            return remote;
        } catch (RuntimeException error) {
            if (cached != null && !cached.servers().isEmpty()) {
                return new DirectorySnapshot(cached.servers(), cached.statuses(),
                        cached.fetchedAtEpochMillis(), true);
            }
            throw error;
        }
    }

    private DirectorySnapshot fetchRemote() {
        JsonArray combined = new JsonArray();
        int totalPages = 1;
        for (int page = 1; page <= Math.min(totalPages, MAX_PAGES); page++) {
            JsonObject response = fetcher.apply(ENDPOINT + page);
            JsonArray data = array(response, "data");
            if (data == null) {
                throw new IllegalStateException("Public server directory returned no data");
            }
            data.forEach(combined::add);
            totalPages = totalPages(response);
            if (data.isEmpty()) {
                break;
            }
        }
        return parseSnapshot(combined, System.currentTimeMillis(), false);
    }

    private DirectorySnapshot readCache() {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!Messages.locale().toLanguageTag().equalsIgnoreCase(text(root, "locale"))) {
                return null;
            }
            JsonArray data = array(root, "data");
            if (data == null) {
                return null;
            }
            return parseSnapshot(data, longValue(root, "fetchedAtEpochMillis"), true);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void writeCache(DirectorySnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("fetchedAtEpochMillis", snapshot.fetchedAtEpochMillis());
        root.addProperty("locale", Messages.locale().toLanguageTag());
        JsonArray data = new JsonArray();
        snapshot.servers().forEach(server -> data.add(toJson(
                server, snapshot.statuses().get(server.address()))));
        root.add("data", data);

        Path normalizedCache = cacheFile.toAbsolutePath().normalize();
        Path parent = normalizedCache.getParent();
        if (parent == null) {
            return;
        }
        Path temporary = normalizedCache.resolveSibling(normalizedCache.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, GsonProvider.pretty().toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, normalizedCache, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredAgain) {
                // A failed cache write must not make the directory unavailable.
            }
        }
    }

    private static DirectorySnapshot parseSnapshot(JsonArray data, long fetchedAt, boolean cached) {
        LinkedHashMap<String, PublicServer> servers = new LinkedHashMap<>();
        Map<String, ServerStatus> statuses = new LinkedHashMap<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            ParsedServer parsed = parseServer(element.getAsJsonObject());
            if (parsed == null) {
                continue;
            }
            String addressKey = parsed.server().address().toLowerCase(Locale.ROOT);
            if (servers.putIfAbsent(addressKey, parsed.server()) == null) {
                statuses.put(parsed.server().address(), parsed.status());
            }
        }
        return new DirectorySnapshot(List.copyOf(servers.values()), Map.copyOf(statuses),
                fetchedAt, cached);
    }

    private static ParsedServer parseServer(JsonObject json) {
        String name = clean(text(json, "name"));
        String host = text(json, "host");
        if (host.isBlank()) {
            host = text(json, "ip");
        }
        int port = intValue(json, "port", 25565);
        if (name.isBlank() || host.isBlank() || port < 1 || port > 65535) {
            return null;
        }

        List<String> types = strings(json, "types");
        if (types.isEmpty()) {
            types = strings(json, "tags");
        }
        String category = classify(types).id();
        String region = countryName(text(json, "country"));
        String description = types.isEmpty()
                ? Messages.get("server.description.public")
                : Messages.format("server.description.types", String.join(" · ", types));
        PublicServer server = new PublicServer(
                name,
                category,
                host,
                port,
                text(json, "version"),
                description,
                region,
                text(json, "url"),
                types,
                iconText(name)
        );
        return new ParsedServer(server, parseStatus(json));
    }

    private static ServerStatus parseStatus(JsonObject json) {
        String state = text(json, "status");
        if ("online".equalsIgnoreCase(state) || booleanValue(json, "isOnline")) {
            return ServerStatus.online(
                    intValue(json, "players_online", intValue(json, "currentPlayers", 0)),
                    intValue(json, "players_total", intValue(json, "maxPlayers", 0)),
                    text(json, "version"));
        }
        if ("offline".equalsIgnoreCase(state)) {
            return ServerStatus.offline();
        }
        return ServerStatus.unknown();
    }

    private static ServerCategory classify(List<String> types) {
        String value = String.join(" ", types).toLowerCase(Locale.ROOT);
        if (containsAny(value, "redstone", "technical", "tech", "automation", "engineering")) {
            return ServerCategory.TECH;
        }
        if (containsAny(value, "pvp", "factions", "prison", "anarchy", "hardcore",
                "lifesteal", "duels", "kitpvp")) {
            return ServerCategory.PVP;
        }
        if (containsAny(value, "minigame", "bedwars", "skywars", "parkour", "pixelmon",
                "creative", "roleplay", "skyblock", "oneblock")) {
            return ServerCategory.ENTERTAINMENT;
        }
        if (containsAny(value, "smp", "vanilla", "towny", "economy", "earth", "crossplay")) {
            return ServerCategory.SMP;
        }
        return ServerCategory.SURVIVAL;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject toJson(PublicServer server, ServerStatus status) {
        JsonObject json = new JsonObject();
        json.addProperty("name", server.name());
        json.addProperty("host", server.ip());
        json.addProperty("port", server.port());
        json.addProperty("version", server.version());
        json.addProperty("country", server.region());
        json.addProperty("url", server.website());
        JsonArray types = new JsonArray();
        server.tags().forEach(types::add);
        json.add("types", types);
        if (status != null) {
            json.addProperty("status", status.state().name().toLowerCase(Locale.ROOT));
            json.addProperty("players_online", status.playersOnline());
            json.addProperty("players_total", status.playersMax());
        }
        return json;
    }

    private static boolean isFresh(long fetchedAt) {
        return fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < CACHE_TTL.toMillis();
    }

    private static int totalPages(JsonObject response) {
        JsonObject meta = object(response, "meta");
        return meta == null ? 1 : Math.max(1, intValue(meta, "total_pages", 1));
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonArray values = array(json, key);
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                result.add(clean(value.getAsString()));
            }
        }
        return List.copyOf(result);
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static int intValue(JsonObject json, String key, int fallback) {
        try {
            JsonElement value = json == null ? null : json.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (RuntimeException ignored) {
            // A malformed server-directory field falls back to its default value.
            return fallback;
        }
    }

    private static long longValue(JsonObject json, String key) {
        try {
            JsonElement value = json == null ? null : json.get(key);
            return value == null || value.isJsonNull() ? 0L : value.getAsLong();
        } catch (RuntimeException ignored) {
            // A malformed server-directory field falls back to 0.
            return 0L;
        }
    }

    private static boolean booleanValue(JsonObject json, String key) {
        try {
            JsonElement value = json == null ? null : json.get(key);
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        } catch (RuntimeException ignored) {
            // A malformed server-directory field falls back to false.
            return false;
        }
    }

    private static String countryName(String country) {
        if (country.isBlank() || country.length() != 2) {
            return country;
        }
        return new Locale("", country.toUpperCase(Locale.ROOT))
                .getDisplayCountry(Messages.locale());
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("§.", "").trim();
    }

    private static String iconText(String name) {
        if (name.isBlank()) {
            return "◈";
        }
        int count = Math.min(2, name.codePointCount(0, name.length()));
        return name.substring(0, name.offsetByCodePoints(0, count)).toUpperCase(Locale.ROOT);
    }

    private static JsonObject fetchJson(String url) {
        try {
            return HttpUtil.getJson(url);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load public server directory", error);
        }
    }

    private record ParsedServer(PublicServer server, ServerStatus status) {
    }

    /** A complete remote or cached directory snapshot. */
    public record DirectorySnapshot(
            List<PublicServer> servers,
            Map<String, ServerStatus> statuses,
            long fetchedAtEpochMillis,
            boolean cached
    ) {
        public DirectorySnapshot {
            servers = List.copyOf(servers);
            statuses = Map.copyOf(statuses);
        }
    }
}
