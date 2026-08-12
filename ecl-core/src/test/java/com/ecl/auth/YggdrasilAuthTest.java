package com.ecl.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNull;

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
}
