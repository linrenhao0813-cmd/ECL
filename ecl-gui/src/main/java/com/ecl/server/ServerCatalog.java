package com.ecl.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 从内置 JSON 资源加载全球公开服务器目录，并提供按分类与关键词过滤。
 * 目录文件位于 classpath 的 {@code servers/servers.json}，可替换或扩展。
 */
public final class ServerCatalog {
    private static final String RESOURCE = "/servers/servers.json";

    private final List<PublicServer> servers;

    private ServerCatalog(List<PublicServer> servers) {
        this.servers = List.copyOf(servers);
    }

    /** 返回按名称排序后的全部服务器（只读视图）。 */
    public List<PublicServer> servers() {
        return servers;
    }

    /**
     * Uses the discovered directory as the primary list and appends bundled entries that are not
     * already present. Addresses are compared case-insensitively.
     */
    public ServerCatalog withDiscoveredServers(List<PublicServer> discovered) {
        LinkedHashMap<String, PublicServer> merged = new LinkedHashMap<>();
        if (discovered != null) {
            discovered.forEach(server -> merged.putIfAbsent(
                    server.address().toLowerCase(Locale.ROOT), server));
        }
        servers.forEach(server -> merged.putIfAbsent(
                server.address().toLowerCase(Locale.ROOT), server));
        return new ServerCatalog(new ArrayList<>(merged.values()));
    }

    /**
     * 按分类与关键词过滤。关键词会匹配名称、简介、标签、地区与版本描述。
     *
     * @param category 目标分类，{@link ServerCategory#ALL} 表示不限分类
     * @param query    搜索关键词，空串表示不限
     */
    public List<PublicServer> filter(ServerCategory category, String query) {
        ServerCategory effective = category == null ? ServerCategory.ALL : category;
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> tokens = needle.isEmpty()
                ? List.of()
                : List.of(needle.split("\\s+"));
        List<PublicServer> result = new ArrayList<>();
        for (PublicServer server : servers) {
            if (effective != ServerCategory.ALL && !effective.id().equalsIgnoreCase(server.category())) {
                continue;
            }
            if (!tokens.isEmpty() && tokens.stream().anyMatch(token -> !matches(server, token))) {
                continue;
            }
            result.add(server);
        }
        return result;
    }

    private static boolean matches(PublicServer server, String needle) {
        if (contains(server.name(), needle)
                || contains(server.description(), needle)
                || contains(server.region(), needle)
                || contains(server.version(), needle)
                || contains(server.ip(), needle)
                || contains(server.category(), needle)
                || contains(server.categoryEnum().label(), needle)) {
            return true;
        }
        for (String tag : server.tags()) {
            if (contains(tag, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 加载并构建目录实例；资源缺失或损坏时回退为空目录，避免启动失败。 */
    public static ServerCatalog load() {
        List<PublicServer> loaded = new ArrayList<>();
        try (InputStream input = ServerCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                JsonArray array = JsonParser.parseReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement element : array) {
                    PublicServer server = parse(element);
                    if (server != null) {
                        loaded.add(server);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 回退到空目录，页面仍可正常渲染。
        }
        loaded.sort(Comparator.comparing(PublicServer::name, String.CASE_INSENSITIVE_ORDER));
        return new ServerCatalog(loaded);
    }

    private static PublicServer parse(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        String name = text(json, "name");
        String ip = text(json, "ip");
        if (name.isBlank() || ip.isBlank()) {
            return null;
        }
        int port = json.has("port") && !json.get("port").isJsonNull()
                ? json.get("port").getAsInt() : 25565;
        if (port < 1 || port > 65535) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        if (json.has("tags") && json.get("tags").isJsonArray()) {
            for (JsonElement tag : json.getAsJsonArray("tags")) {
                if (tag.isJsonPrimitive()) {
                    tags.add(tag.getAsString());
                }
            }
        }
        return new PublicServer(
                name,
                text(json, "category"),
                ip,
                port,
                text(json, "version"),
                text(json, "description"),
                text(json, "region"),
                text(json, "website"),
                tags,
                text(json, "iconText")
        );
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
