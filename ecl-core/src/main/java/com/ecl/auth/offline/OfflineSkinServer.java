package com.ecl.auth.offline;

import com.ecl.ECLConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal authlib-injector compatible Yggdrasil server that serves locally imported offline
 * skins to the running game.
 *
 * <p>When an offline account has a local skin, the launcher starts this server on the loopback
 * interface (never reachable from the network) and passes
 * {@code -javaagent:authlib-injector.jar=<baseUrl> -Dauthlibinjector.side=client} to the game.
 * authlib-injector then redirects the game's session-server calls to this server, which returns
 * the player profile with a signed {@code textures} property pointing at the PNG served by
 * {@code /textures/<sha1>}. No mods and no premium account are required.</p>
 */
public final class OfflineSkinServer implements AutoCloseable {

    private static final int MAX_TEXTURES = 16;
    private static final long MAX_TEXTURE_BYTES = 1024 * 1024;
    private static volatile OfflineSkinServer shared;
    private static int sharedUsers;

    private final HttpServer server;
    private final KeyPair keyPair;
    private final Map<String, byte[]> textures = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Character> charactersByUuid = new ConcurrentHashMap<>();
    private final Map<String, Character> charactersByName = new ConcurrentHashMap<>();
    private final String baseUrl;
    private final AtomicBoolean closed = new AtomicBoolean();

    OfflineSkinServer() throws IOException {
        this.keyPair = generateKeyPair();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    /** Acquire the process-wide server until the returned lease is closed. */
    static synchronized Lease acquire() throws IOException {
        if (shared == null || shared.closed.get()) {
            shared = new OfflineSkinServer();
            sharedUsers = 0;
        }
        sharedUsers++;
        return new Lease(shared);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Register (or replace) the character served for this offline player. */
    public synchronized Registration registerCharacter(String uuid, String username,
                                                       Path skinPng, boolean slim) throws IOException {
        long size = Files.size(skinPng);
        if (size <= 0 || size > MAX_TEXTURE_BYTES) {
            throw new IOException("Offline skin must be between 1 byte and 1 MB");
        }
        byte[] png = Files.readAllBytes(skinPng);
        if (png.length == 0 || png.length > MAX_TEXTURE_BYTES) {
            throw new IOException("Offline skin changed while it was being read");
        }
        String hash = sha1Hex(png);
        synchronized (textures) {
            textures.put(hash, png);
        }
        Character character = new Character(uuid, username, hash, slim);
        charactersByUuid.put(uuid, character);
        charactersByName.put(username, character);
        pruneUnusedTextures();
        return new Registration(this, character);
    }

    @Override
    public void close() {
        synchronized (OfflineSkinServer.class) {
            if (shared == this) {
                shared = null;
                sharedUsers = 0;
            }
        }
        stopServer();
    }

    private void stopServer() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
        }
    }

    private static synchronized void release(OfflineSkinServer server) {
        if (shared != server || sharedUsers == 0) {
            return;
        }
        sharedUsers--;
        if (sharedUsers == 0) {
            shared = null;
            server.stopServer();
        }
    }

    /** One active game process' ownership of the shared skin server. */
    static final class Lease implements AutoCloseable {
        private final OfflineSkinServer server;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(OfflineSkinServer server) {
            this.server = server;
        }

        OfflineSkinServer server() {
            return server;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                OfflineSkinServer.release(server);
            }
        }
    }

    /** One running game's registration of a character and its texture. */
    static final class Registration implements AutoCloseable {
        private final OfflineSkinServer server;
        private final Character character;
        private final AtomicBoolean removed = new AtomicBoolean();

        private Registration(OfflineSkinServer server, Character character) {
            this.server = server;
            this.character = character;
        }

        @Override
        public void close() {
            if (removed.compareAndSet(false, true)) {
                server.unregister(character);
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isTrustedRequest(exchange)) {
                respond(exchange, 403, "Forbidden".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            switch (method + " " + path) {
                case "GET /" -> respondJson(exchange, 200, metadata());
                case "GET /status" -> respondJson(exchange, 200, status());
                case "POST /api/profiles/minecraft" -> profiles(exchange);
                case "GET /sessionserver/session/minecraft/hasJoined" -> hasJoined(exchange);
                case "POST /sessionserver/session/minecraft/join" -> respond(exchange, 204, "".getBytes(StandardCharsets.UTF_8), "text/plain");
                default -> {
                    if ("GET".equals(method) && path.startsWith("/sessionserver/session/minecraft/profile/")) {
                        profile(exchange, path);
                    } else if ("GET".equals(method) && path.startsWith("/textures/")) {
                        texture(exchange, path);
                    } else {
                        respond(exchange, 404, "Not Found".getBytes(StandardCharsets.UTF_8), "text/plain");
                    }
                }
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * The service is intentionally loopback-only, but loopback alone is not enough: a DNS-rebinding
     * page can still connect to 127.0.0.1. Require the expected Host and reject browser-originated
     * cross-origin requests before any profile or texture data is exposed.
     */
    private boolean isTrustedRequest(HttpExchange exchange) {
        InetAddress remote = exchange.getRemoteAddress() == null
                ? null : exchange.getRemoteAddress().getAddress();
        if (remote == null || !remote.isLoopbackAddress()) {
            return false;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!isAllowedHost(host)) {
            return false;
        }
        return sameOriginOrAbsent(exchange.getRequestHeaders().getFirst("Origin"))
                && sameOriginOrAbsent(exchange.getRequestHeaders().getFirst("Referer"));
    }

    private boolean isAllowedHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        int port = server.getAddress().getPort();
        return host.equals("127.0.0.1:" + port)
                || host.equals("localhost:" + port)
                || host.equals("[::1]:" + port);
    }

    private boolean sameOriginOrAbsent(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            java.net.URI origin = java.net.URI.create(value.trim());
            java.net.URI expected = java.net.URI.create(baseUrl);
            int port = origin.getPort() < 0 ? expected.getPort() : origin.getPort();
            return "http".equalsIgnoreCase(origin.getScheme())
                    && port == expected.getPort()
                    && ("127.0.0.1".equalsIgnoreCase(origin.getHost())
                    || "localhost".equalsIgnoreCase(origin.getHost())
                    || "[::1]".equalsIgnoreCase(origin.getHost())
                    || "::1".equalsIgnoreCase(origin.getHost()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private JsonObject metadata() {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", "ECL");
        meta.addProperty("implementationName", ECLConfig.LAUNCHER_NAME);
        meta.addProperty("implementationVersion", ECLConfig.LAUNCHER_VERSION);
        meta.addProperty("feature.non_email_login", true);

        JsonArray skinDomains = new JsonArray();
        skinDomains.add("127.0.0.1");
        skinDomains.add("localhost");

        JsonObject root = new JsonObject();
        root.addProperty("signaturePublickey", publicKeyPem());
        root.add("skinDomains", skinDomains);
        root.add("meta", meta);
        return root;
    }

    private synchronized JsonObject status() {
        JsonObject status = new JsonObject();
        status.addProperty("user.count", charactersByUuid.size());
        status.addProperty("token.count", 0);
        status.addProperty("pendingAuthentication.count", 0);
        return status;
    }

    private void profiles(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonArray response = new JsonArray();
        try {
            JsonArray names = JsonParser.parseString(body).getAsJsonArray();
            for (var element : names) {
                Character character = characterByName(element.getAsString());
                if (character != null) {
                    response.add(character.simpleResponse());
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed request body -> empty profile list
        }
        respondJson(exchange, 200, response);
    }

    private void hasJoined(HttpExchange exchange) throws IOException {
        String username = exchange.getRequestURI().getRawQuery() == null ? null
                : queryParameter(exchange.getRequestURI().getRawQuery(), "username");
        Character character = username == null ? null : characterByName(username);
        if (character == null) {
            respond(exchange, 204, new byte[0], "text/plain");
        } else {
            respondJson(exchange, 200, character.completeResponse(baseUrl, this::sign));
        }
    }

    private void profile(HttpExchange exchange, String path) throws IOException {
        String uuid = path.substring("/sessionserver/session/minecraft/profile/".length());
        Character character = characterByUuid(uuid);
        if (character == null) {
            respond(exchange, 204, new byte[0], "text/plain");
        } else {
            respondJson(exchange, 200, character.completeResponse(baseUrl, this::sign));
        }
    }

    private void texture(HttpExchange exchange, String path) throws IOException {
        String hash = path.substring("/textures/".length());
        byte[] png;
        synchronized (textures) {
            png = textures.get(hash);
        }
        if (png == null) {
            respond(exchange, 404, "Not Found".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Etag", "\"" + hash + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "max-age=2592000, public");
        respond(exchange, 200, png, "image/png");
    }

    private synchronized void unregister(Character character) {
        charactersByUuid.remove(character.uuid, character);
        charactersByName.remove(character.name, character);
        pruneUnusedTextures();
    }

    private synchronized Character characterByName(String username) {
        return charactersByName.get(username);
    }

    private synchronized Character characterByUuid(String uuid) {
        return charactersByUuid.get(uuid);
    }

    private void pruneUnusedTextures() {
        synchronized (textures) {
            Iterator<String> hashes = textures.keySet().iterator();
            while (textures.size() > MAX_TEXTURES && hashes.hasNext()) {
                String candidate = hashes.next();
                boolean referenced = charactersByUuid.values().stream()
                        .anyMatch(character -> character.textureHash.equals(candidate));
                if (!referenced) {
                    hashes.remove();
                }
            }
        }
    }

    // === Response helpers ===

    private static void respondJson(HttpExchange exchange, int status, JsonObject value) throws IOException {
        respond(exchange, status, value.toString().getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void respondJson(HttpExchange exchange, int status, JsonArray value) throws IOException {
        respond(exchange, status, value.toString().getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static String queryParameter(String query, String name) {
        for (String part : query.split("&")) {
            int index = part.indexOf('=');
            if (index > 0 && name.equals(part.substring(0, index))) {
                try {
                    return URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException malformedEncoding) {
                    return null;
                }
            }
        }
        return null;
    }

    // === Character model and signatures ===

    private static final class Character {
        private final String uuid;      // compact, no dashes
        private final String name;
        private final String textureHash;
        private final boolean slim;

        private Character(String uuid, String name, String textureHash, boolean slim) {
            this.uuid = uuid;
            this.name = name;
            this.textureHash = textureHash;
            this.slim = slim;
        }

        JsonObject simpleResponse() {
            JsonObject response = new JsonObject();
            response.addProperty("id", uuid);
            response.addProperty("name", name);
            return response;
        }

        JsonObject completeResponse(String rootUrl, java.util.function.Function<String, String> signer) {
            JsonObject skinTexture = new JsonObject();
            skinTexture.addProperty("url", rootUrl + "/textures/" + textureHash);
            if (slim) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("model", "slim");
                skinTexture.add("metadata", metadata);
            }

            JsonObject textures = new JsonObject();
            textures.add("SKIN", skinTexture);

            JsonObject payload = new JsonObject();
            payload.addProperty("timestamp", System.currentTimeMillis());
            payload.addProperty("profileId", uuid);
            payload.addProperty("profileName", name);
            payload.add("textures", textures);

            String value = Base64.getEncoder().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject property = new JsonObject();
            property.addProperty("name", "textures");
            property.addProperty("value", value);
            property.addProperty("signature", signer.apply(value));

            JsonArray properties = new JsonArray();
            properties.add(property);

            JsonObject response = new JsonObject();
            response.addProperty("id", uuid);
            response.addProperty("name", name);
            response.add("properties", properties);
            return response;
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            // authlib-injector rejects small keys; 2048 matches the minimum it accepts comfortably
            generator.initialize(2048, new SecureRandom());
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("RSA not available", impossible);
        }
    }

    private String publicKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    private String sign(String data) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(keyPair.getPrivate(), new SecureRandom());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot sign texture property", failure);
        }
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 not available", impossible);
        }
    }
}
