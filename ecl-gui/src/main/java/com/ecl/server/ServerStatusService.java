package com.ecl.server;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过公开的 mcsrvstat.us 状态接口探测服务器在线状态。
 * 探测失败时回退到 {@link ServerStatus#unknown()}，不会抛出异常。
 * 失败的探测地址会进入 60 秒冷却期，避免高频刷新反复请求不可达地址。
 */
public final class ServerStatusService {
    private static final String ENDPOINT = "https://api.mcsrvstat.us/2/";
    private static final long UNKNOWN_RETRY_COOLDOWN_MS = 60_000L;
    private static final Map<String, Long> FAILURE_TIMES = new ConcurrentHashMap<>();

    private ServerStatusService() {
    }

    /** 探测一台服务器的在线状态（同步，供后台线程调用）。 */
    public static ServerStatus fetch(PublicServer server) {
        long startedAt = System.currentTimeMillis();
        try {
            ServerStatus status = parse(HttpUtil.getJson(url(server)));
            if (status.state() != ServerStatusState.UNKNOWN) {
                FAILURE_TIMES.remove(server.address());
            }
            return status;
        } catch (IOException | RuntimeException ignored) {
            FAILURE_TIMES.put(server.address(), startedAt);
            return ServerStatus.unknown();
        }
    }

    /**
     * 是否允许对该地址发起新的探测。地址处于 60 秒失败冷却期内时返回 false，
     * 调用方应沿用现有的 UNKNOWN 状态并跳过网络请求。
     */
    public static boolean shouldProbe(PublicServer server) {
        if (server == null || server.address() == null || server.address().isBlank()) {
            return false;
        }
        Long failedAt = FAILURE_TIMES.get(server.address());
        return failedAt == null
                || System.currentTimeMillis() - failedAt >= UNKNOWN_RETRY_COOLDOWN_MS;
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
