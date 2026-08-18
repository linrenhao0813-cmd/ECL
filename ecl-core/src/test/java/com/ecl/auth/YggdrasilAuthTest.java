package com.ecl.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YggdrasilAuthTest {
    @Test
    void logoutClearsTheStoredPassword() throws Exception {
        YggdrasilAuth auth = new YggdrasilAuth("https://example.invalid/authserver/");
        auth.setCredentials("player@example.invalid", "secret");

        auth.logout();

        Field password = YggdrasilAuth.class.getDeclaredField("password");
        password.setAccessible(true);
        assertNull(password.get(auth));
    }

    @Test
    void requiresHttpsExceptForExplicitLoopbackServers() {
        assertEquals("https://example.invalid/authserver/",
                YggdrasilAuth.normalizeAuthServer("https://example.invalid/authserver"));
        assertEquals("http://127.0.0.1:8080/",
                YggdrasilAuth.normalizeAuthServer("http://127.0.0.1:8080"));
        assertThrows(IllegalArgumentException.class,
                () -> YggdrasilAuth.normalizeAuthServer("http://example.invalid/authserver"));
        assertThrows(IllegalArgumentException.class,
                () -> YggdrasilAuth.normalizeAuthServer("https://user@example.invalid/authserver"));
    }
}
