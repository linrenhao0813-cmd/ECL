package com.ecl.auth.offline;

import java.io.IOException;

/** Serializes ownership of the process-wide shared offline skin server. */
final class OfflineSkinServerLeases {
    private static OfflineSkinServer shared;
    private static int users;

    private OfflineSkinServerLeases() {
    }

    static synchronized OfflineSkinServer acquire() throws IOException {
        if (shared == null || shared.isClosed()) {
            shared = new OfflineSkinServer();
            users = 0;
        }
        users++;
        return shared;
    }

    static synchronized void release(OfflineSkinServer server) {
        if (shared != server || users == 0) return;
        if (--users == 0) {
            shared = null;
            server.stopServer();
        }
    }

    static synchronized void clear(OfflineSkinServer server) {
        if (shared == server) {
            shared = null;
            users = 0;
        }
    }
}
