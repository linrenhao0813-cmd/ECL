package com.ecl.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerAddressTest {

    @Test
    void rejectsPortsOutsideTcpRange() {
        assertNull(ServerAddress.parse("localhost:0").port());
        assertNull(ServerAddress.parse("localhost:65536").port());
        assertEquals(25565, ServerAddress.parse("localhost:25565").port());
    }

    @Test
    void parsesBracketedIpv6WithValidPort() {
        ServerAddress address = ServerAddress.parse("[::1]:25565");

        assertEquals("::1", address.host());
        assertEquals(25565, address.port());
    }
}
