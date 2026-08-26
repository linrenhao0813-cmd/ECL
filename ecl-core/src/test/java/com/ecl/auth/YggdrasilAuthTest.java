package com.ecl.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

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
                () -> YggdrasilAuth.normalizeAuthServer("http://127.attacker.example/authserver"));
        assertThrows(IllegalArgumentException.class,
                () -> YggdrasilAuth.normalizeAuthServer("http://127.0.0.1.attacker.example/authserver"));
        assertThrows(IllegalArgumentException.class,
                () -> YggdrasilAuth.normalizeAuthServer("https://user@example.invalid/authserver"));
    }

    @Test
    void authenticationStreamsAndEscapesMutablePasswordPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/authenticate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = """
                    {"accessToken":"access","clientToken":"returned-client",
                     "selectedProfile":{"id":"profile-id","name":"Player"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            char[] password = {'s', 'e', 'c', 'r', 'e', 't', '"', '\\', '\n', '密'};
            YggdrasilAuth auth = new YggdrasilAuth(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");

            auth.authenticate("player@example.invalid", password);

            JsonObject payload = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals(new String(password), payload.get("password").getAsString());
            assertEquals("player@example.invalid", payload.get("username").getAsString());
            Field storedPassword = YggdrasilAuth.class.getDeclaredField("password");
            storedPassword.setAccessible(true);
            assertNull(storedPassword.get(auth));
        } finally {
            server.stop(0);
        }
    }
}
