package com.ecl.auth.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Routes only trusted loopback authlib-injector requests to local skin data. */
final class OfflineSkinHttpHandler implements HttpHandler {
    private static final int MAX_PROFILE_REQUEST_BYTES = 64 * 1024;
    private final OfflineSkinCharacterRegistry registry;
    private final OfflineSkinTextureSigner signer;
    private final String baseUrl;
    private final int port;

    OfflineSkinHttpHandler(OfflineSkinCharacterRegistry registry, OfflineSkinTextureSigner signer,
                           String baseUrl, int port) {
        this.registry = registry;
        this.signer = signer;
        this.baseUrl = baseUrl;
        this.port = port;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isTrustedRequest(exchange)) {
                respond(exchange, 403, "Forbidden".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            switch (exchange.getRequestMethod() + " " + path) {
                case "GET /" -> respondJson(exchange, 200, OfflineSkinYggdrasilResponses.metadata(signer));
                case "GET /status" -> respondJson(exchange, 200,
                        OfflineSkinYggdrasilResponses.status(registry.userCount()));
                case "POST /api/profiles/minecraft" -> profiles(exchange);
                case "GET /sessionserver/session/minecraft/hasJoined" -> hasJoined(exchange);
                case "POST /sessionserver/session/minecraft/join" -> respond(exchange, 204, new byte[0], "text/plain");
                default -> {
                    if ("GET".equals(exchange.getRequestMethod())
                            && path.startsWith("/sessionserver/session/minecraft/profile/")) profile(exchange, path);
                    else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/textures/")) texture(exchange, path);
                    else respond(exchange, 404, "Not Found".getBytes(StandardCharsets.UTF_8), "text/plain");
                }
            }
        } finally { exchange.close(); }
    }

    private boolean isTrustedRequest(HttpExchange exchange) {
        InetAddress remote = exchange.getRemoteAddress() == null ? null : exchange.getRemoteAddress().getAddress();
        if (remote == null || !remote.isLoopbackAddress()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) return false;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (!(normalized.equals("127.0.0.1:" + port) || normalized.equals("localhost:" + port)
                || normalized.equals("[::1]:" + port))) return false;
        return sameOriginOrAbsent(exchange.getRequestHeaders().getFirst("Origin"))
                && sameOriginOrAbsent(exchange.getRequestHeaders().getFirst("Referer"));
    }

    private boolean sameOriginOrAbsent(String value) {
        if (value == null || value.isBlank()) return true;
        try {
            URI origin = URI.create(value.trim());
            int originPort = origin.getPort() < 0 ? port : origin.getPort();
            String host = origin.getHost();
            return "http".equalsIgnoreCase(origin.getScheme()) && originPort == port
                    && ("127.0.0.1".equalsIgnoreCase(host) || "localhost".equalsIgnoreCase(host)
                    || "[::1]".equalsIgnoreCase(host) || "::1".equalsIgnoreCase(host));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private void profiles(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_PROFILE_REQUEST_BYTES + 1);
        if (body.length > MAX_PROFILE_REQUEST_BYTES) {
            respond(exchange, 413, "Request body too large".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        JsonArray response = new JsonArray();
        try {
            for (var name : JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonArray()) {
                var character = registry.byName(name.getAsString());
                if (character != null) response.add(OfflineSkinYggdrasilResponses.simpleProfile(character));
            }
        } catch (RuntimeException ignored) { }
        respondJson(exchange, 200, response);
    }

    private void hasJoined(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String username = query == null ? null : queryParameter(query, "username");
        profileResponse(exchange, username == null ? null : registry.byName(username));
    }
    private void profile(HttpExchange exchange, String path) throws IOException {
        profileResponse(exchange, registry.byUuid(path.substring("/sessionserver/session/minecraft/profile/".length())));
    }
    private void profileResponse(HttpExchange exchange, OfflineSkinYggdrasilResponses.Character character) throws IOException {
        if (character == null) respond(exchange, 204, new byte[0], "text/plain");
        else respondJson(exchange, 200, OfflineSkinYggdrasilResponses.completeProfile(character, baseUrl, signer));
    }
    private void texture(HttpExchange exchange, String path) throws IOException {
        String hash = path.substring("/textures/".length());
        byte[] png = registry.texture(hash);
        if (png == null) { respond(exchange, 404, "Not Found".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
        exchange.getResponseHeaders().set("Etag", "\"" + hash + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "max-age=2592000, public");
        respond(exchange, 200, png, "image/png");
    }
    static String queryParameter(String query, String name) {
        for (String part : query.split("&")) {
            int index = part.indexOf('=');
            if (index > 0 && name.equals(part.substring(0, index))) {
                try { return URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8); }
                catch (IllegalArgumentException malformed) { return null; }
            }
        }
        return null;
    }
    private static void respondJson(HttpExchange exchange, int status, Object value) throws IOException {
        respond(exchange, status, value.toString().getBytes(StandardCharsets.UTF_8), "application/json");
    }
    private static void respond(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (status == 204) { exchange.sendResponseHeaders(status, -1); return; }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
