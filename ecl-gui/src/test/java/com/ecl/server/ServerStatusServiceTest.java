package com.ecl.server;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerStatusServiceTest {
    @Test
    void parsesOnlineStatusAndPlayerCounts() {
        ServerStatus status = ServerStatusService.parse(JsonParser.parseString("""
                {
                  "online": true,
                  "players": {"online": 1234, "max": 5000},
                  "version": "1.21.4"
                }
                """).getAsJsonObject());

        assertEquals(ServerStatusState.ONLINE, status.state());
        assertEquals(1234, status.playersOnline());
        assertEquals(5000, status.playersMax());
        assertEquals("1.21.4", status.version());
    }

    @Test
    void missingOrFalseOnlineStatusIsOffline() {
        ServerStatus status = ServerStatusService.parse(JsonParser.parseString("""
                {"online": false}
                """).getAsJsonObject());

        assertEquals(ServerStatusState.OFFLINE, status.state());
    }

    @Test
    void statusUrlIncludesCustomPort() {
        PublicServer server = new PublicServer(
                "Test", "pvp", "play.example.org", 25570, "1.21", "", "", "",
                List.of(), "T");

        assertEquals("https://api.mcsrvstat.us/2/play.example.org:25570",
                ServerStatusService.url(server));
    }
}
