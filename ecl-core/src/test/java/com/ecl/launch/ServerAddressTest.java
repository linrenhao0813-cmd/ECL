package com.ecl.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAddressTest {

    @Test
    void parsesPlainHost() {
        ServerAddress parsed = ServerAddress.parse("play.example.com");
        assertEquals("play.example.com", parsed.host());
        assertNull(parsed.port());
        assertTrue(parsed.hasServer());
    }

    @Test
    void parsesHostAndPort() {
        ServerAddress parsed = ServerAddress.parse("play.example.com:25570");
        assertEquals("play.example.com", parsed.host());
        assertEquals(25570, parsed.port());
    }

    @Test
    void parsesBracketedIpv6WithPort() {
        ServerAddress parsed = ServerAddress.parse("[::1]:25565");
        assertEquals("::1", parsed.host());
        assertEquals(25565, parsed.port());
    }

    @Test
    void parsesBracketedIpv6WithoutPort() {
        ServerAddress parsed = ServerAddress.parse("[2a00::8]");
        assertEquals("2a00::8", parsed.host());
        assertNull(parsed.port());
    }

    @Test
    void rejectsNonNumericPortButKeepsHost() {
        ServerAddress parsed = ServerAddress.parse("example.com:ab");
        assertEquals("example.com", parsed.host());
        assertNull(parsed.port());
        assertTrue(parsed.hasServer());
    }

    @Test
    void emptyAndBlankInputMeanNoServer() {
        assertFalse(ServerAddress.parse("").hasServer());
        assertFalse(ServerAddress.parse("   ").hasServer());
        assertFalse(ServerAddress.parse(null).hasServer());
    }

    @Test
    void overLongPortIsDropped() {
        ServerAddress parsed = ServerAddress.parse("example.com:123456");
        assertEquals("example.com", parsed.host());
        assertNull(parsed.port());
    }
}