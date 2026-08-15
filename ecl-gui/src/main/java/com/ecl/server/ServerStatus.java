package com.ecl.server;

/** 一台服务器的实时状态快照。 */
public record ServerStatus(
        ServerStatusState state,
        int playersOnline,
        int playersMax,
        String version
) {
    public static ServerStatus online(int playersOnline, int playersMax, String version) {
        return new ServerStatus(ServerStatusState.ONLINE,
                Math.max(0, playersOnline), Math.max(0, playersMax), version == null ? "" : version);
    }

    public static ServerStatus offline() {
        return new ServerStatus(ServerStatusState.OFFLINE, 0, 0, "");
    }

    public static ServerStatus unknown() {
        return new ServerStatus(ServerStatusState.UNKNOWN, 0, 0, "");
    }
}
