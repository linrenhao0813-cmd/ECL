package com.ecl.server;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * 通过公开的 mcsrvstat.us 状态接口探测服务器在线状态。
 * 探测失败时回退到 {@link ServerStatus#unknown()}，不会抛出异常。
 */
public final class ServerStatusService {
    private static final String ENDPOINT = "https://api.mcsrvstat.us/2/";

    private ServerStatusService() {
    }

    /** 探测一台服务器的在线状态（同步，供后台线程调用）。 */
    public static ServerStatus fetch(PublicServer server) {
        try {
            return parse(HttpUtil.getJson(url(server)));
        } catch (IOException | RuntimeException ignored) {
            return ServerStatus.unknown();
        }
    }

    /** 构造探测地址，默认端口省略端口号。 */
    static String url(PublicServer server) {
        return ENDPOINT + server.address();
    }

    static ServerStatus parse(JsonObject json) {
        if (json == null) {
            return ServerStatus.unknown();
        }
        boolean online = json.has("online") && !json.get("online").isJsonNull()
                && json.get("online").getAsBoolean();
        if (!online) {
            return ServerStatus.offline();
        }
        int playersOnline = 0;
        int playersMax = 0;
        if (json.has("players") && json.get("players").isJsonObject()) {
            JsonObject players = json.getAsJsonObject("players");
            playersOnline = intField(players, "online");
            playersMax = intField(players, "max");
        }
        String version = json.has("version") && !json.get("version").isJsonNull()
                ? json.get("version").getAsString() : "";
        return ServerStatus.online(playersOnline, playersMax, version);
    }

    private static int intField(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            try {
                return json.get(key).getAsInt();
            } catch (NumberFormatException ignored) {
                // 回退为 0
            }
        }
        return 0;
    }
}
