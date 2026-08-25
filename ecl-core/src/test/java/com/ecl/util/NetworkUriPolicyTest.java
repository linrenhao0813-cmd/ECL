package com.ecl.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkUriPolicyTest {
    @Test
    void recognizesOnlyLiteralLoopbackHosts() {
        assertTrue(NetworkUriPolicy.isLoopbackHostLiteral("127.0.0.1"));
        assertTrue(NetworkUriPolicy.isLoopbackHostLiteral("127.255.1.2"));
        assertTrue(NetworkUriPolicy.isLoopbackHostLiteral("localhost"));
        assertTrue(NetworkUriPolicy.isLoopbackHostLiteral("::1"));
        assertFalse(NetworkUriPolicy.isLoopbackHostLiteral("127.attacker.example"));
        assertFalse(NetworkUriPolicy.isLoopbackHostLiteral("127.0.0.999"));
    }

    @Test
    void allowsHttpsAndLoopbackHttpButRejectsRemoteHttp() throws Exception {
        URI https = URI.create("https://cdn.example/file.jar");
        assertEquals(https, NetworkUriPolicy.requireHttpsOrLoopbackHttp(https, "download"));
        assertEquals("127.0.0.1", NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                URI.create("http://127.0.0.1:8080/file.jar"), "download").getHost());
        assertThrows(IOException.class, () -> NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                URI.create("http://example.invalid/file.jar"), "download"));
    }

    @Test
    void enforcesExactTrustedHosts() throws Exception {
        URI trusted = URI.create("https://cdn.modrinth.com/data/file.jar");
        assertEquals(trusted, NetworkUriPolicy.requireAllowedDownload(
                trusted, Set.of("cdn.modrinth.com"), "download"));
        assertThrows(IOException.class, () -> NetworkUriPolicy.requireAllowedDownload(
                URI.create("https://cdn.modrinth.com.attacker.example/file.jar"),
                Set.of("cdn.modrinth.com"), "download"));
    }
}
