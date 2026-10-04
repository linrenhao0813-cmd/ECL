package com.ecl.auth;

import com.ecl.exception.AuthException;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Yggdrasil authentication for third-party authlib servers.
 * Compatible with LittleSkin, Blessing Skin, and other authlib-injector based servers.
 */
public final class YggdrasilAuth implements AuthProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(YggdrasilAuth.class);

    private final String authServer;
    private String username;
    private char[] password;
    private String uuid;
    private String accessToken;
    private String clientToken;
    private boolean loggedIn;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public YggdrasilAuth(String authServer) {
        this.authServer = normalizeAuthServer(authServer);
        this.clientToken = UUID.randomUUID().toString().replace("-", "");
    }

    static String normalizeAuthServer(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Yggdrasil authentication server is blank");
        }
        try {
            URI parsed = new URI(value.trim());
            String scheme = parsed.getScheme() == null
                    ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost();
            if (host == null || host.isBlank() || parsed.getUserInfo() != null
                    || parsed.getQuery() != null || parsed.getFragment() != null) {
                throw new IllegalArgumentException("Invalid Yggdrasil authentication server URL");
            }
            if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopbackHost(host))) {
                throw new IllegalArgumentException(
                        "Yggdrasil authentication requires HTTPS (HTTP is allowed only for localhost)");
            }
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            if (!path.endsWith("/")) {
                path += "/";
            }
            return new URI(scheme, null, host, parsed.getPort(), path, null, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid Yggdrasil authentication server URL", error);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return NetworkUriPolicy.isLoopbackHostLiteral(host);
    }

    public YggdrasilAuth(String authServer, String username, String uuid,
                         String accessToken, String clientToken) {
        this(authServer);
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        if (clientToken != null && !clientToken.isBlank()) this.clientToken = clientToken;
        this.loggedIn = accessToken != null && !accessToken.isBlank();
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setCredentials(String username, String password) {
        char[] mutablePassword = password == null ? null : password.toCharArray();
        try {
            setCredentials(username, mutablePassword);
        } finally {
            if (mutablePassword != null) {
                Arrays.fill(mutablePassword, '\0');
            }
        }
    }

    public void setCredentials(String username, char[] password) {
        clearPassword();
        this.username = username;
        this.password = password == null ? null : password.clone();
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getUUID() {
        return uuid;
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public AuthType getType() {
        return AuthType.YGGDRASIL;
    }

    @Override
    public boolean isLoggedIn() {
        return loggedIn;
    }

    @Override
    public void login() {
        if (username == null || password == null) {
            throw new IllegalStateException("Username and password must be set before login");
        }
        try {
            authenticate(username, password);
        } catch (IOException e) {
            throw new AuthException("Yggdrasil authentication failed", e);
        }
    }

    @Override
    public void logout() {
        if (accessToken != null) {
            try {
                JsonObject payload = tokenPayload();
                postJson(authServer + "invalidate", payload);
            } catch (IOException e) {
                LOGGER.warn("Yggdrasil logout invalidation failed for {}", authServer, e);
            }
        }
        loggedIn = false;
        uuid = null;
        accessToken = null;
        clearPassword();
    }

    private void clearPassword() {
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
    }

    public void authenticate(String username, char[] password) throws IOException {
        byte[] payload = null;
        try {
            payload = authenticationPayload(username, password);
            final byte[] requestBody = payload;
            String response = withPrivateNetwork(
                    () -> HttpUtil.postJsonBytes(authServer + "authenticate", requestBody));
            JsonObject json = parseResponseObject(response, "authenticate");

            if (json.has("error")) {
                String message = json.has("errorMessage") ? json.get("errorMessage").getAsString() : json.get("error").getAsString();
                throw new IOException("Authentication failed: " + message);
            }

            this.accessToken = requireString(json, "accessToken");
            this.clientToken = requireString(json, "clientToken");

            JsonElement selectedElement = json.get("selectedProfile");
            if (selectedElement != null && !selectedElement.isJsonNull()
                    && !selectedElement.isJsonObject()) {
                throw new IOException("Authentication response has invalid selectedProfile");
            }
            JsonObject profile = selectedElement == null || selectedElement.isJsonNull()
                    ? null : selectedElement.getAsJsonObject();
            JsonElement availableElement = json.get("availableProfiles");
            if (profile == null && availableElement != null && !availableElement.isJsonNull()
                    && !availableElement.isJsonArray()) {
                throw new IOException("Authentication response has invalid availableProfiles");
            }
            if (profile == null && availableElement != null && availableElement.isJsonArray()
                    && !availableElement.getAsJsonArray().isEmpty()) {
                JsonElement first = availableElement.getAsJsonArray().get(0);
                if (!first.isJsonObject()) {
                    throw new IOException("Authentication response has invalid available profile");
                }
                profile = first.getAsJsonObject();
            }

            if (profile != null) {
                this.uuid = requireString(profile, "id");
                this.username = requireString(profile, "name");
            }

            this.loggedIn = true;

        } finally {
            if (payload != null) {
                Arrays.fill(payload, (byte) 0);
            }
            clearPassword();
        }
    }

    public boolean validate() throws IOException {
        if (accessToken == null) {
            return false;
        }

        HttpUtil.Response response = withPrivateNetwork(
                () -> HttpUtil.postJsonResponse(authServer + "validate", tokenPayload()));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return true;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return false;
        }
        response.requireSuccess();
        return true;
    }

    public void refresh() throws IOException {
        if (accessToken == null) {
            throw new IllegalStateException("No access token to refresh");
        }

        JsonObject payload = tokenPayload();
        String response = postJson(authServer + "refresh", payload);
        JsonObject json = parseResponseObject(response, "refresh");

        if (json.has("accessToken")) {
            this.accessToken = json.get("accessToken").getAsString();
        }
        if (json.has("selectedProfile") && !json.get("selectedProfile").isJsonNull()) {
            if (!json.get("selectedProfile").isJsonObject()) {
                throw new IOException("Refresh response has invalid selectedProfile");
            }
            JsonObject profile = json.getAsJsonObject("selectedProfile");
            this.uuid = profile.get("id").getAsString();
            this.username = profile.get("name").getAsString();
        }
        this.loggedIn = true;
    }

    private String postJson(String urlStr, JsonObject body) throws IOException {
        return withPrivateNetwork(() -> HttpUtil.postJson(urlStr, body));
    }

    private static <T> T withPrivateNetwork(IoAction<T> action) throws IOException {
        try (AutoCloseable ignored = NetworkUriPolicy.allowPrivateNetworkHttp()) {
            return action.run();
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(error);
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }

    private byte[] authenticationPayload(String username, char[] password) throws IOException {
        try (SensitiveByteArrayOutputStream output = new SensitiveByteArrayOutputStream();
             Writer writer = new OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write("{\"agent\":{\"name\":\"Minecraft\",\"version\":1},\"username\":");
            writeJsonString(writer, username);
            writer.write(",\"password\":");
            writeJsonString(writer, password);
            writer.write(",\"clientToken\":");
            writeJsonString(writer, clientToken);
            writer.write('}');
            writer.flush();
            return output.snapshot();
        }
    }

    private static void writeJsonString(Writer writer, CharSequence value) throws IOException {
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            writeJsonCharacter(writer, value.charAt(i));
        }
        writer.write('"');
    }

    private static void writeJsonString(Writer writer, char[] value) throws IOException {
        writer.write('"');
        for (char character : value) {
            writeJsonCharacter(writer, character);
        }
        writer.write('"');
    }

    private static void writeJsonCharacter(Writer writer, char character) throws IOException {
        switch (character) {
            case '"' -> writer.write("\\\"");
            case '\\' -> writer.write("\\\\");
            case '\b' -> writer.write("\\b");
            case '\f' -> writer.write("\\f");
            case '\n' -> writer.write("\\n");
            case '\r' -> writer.write("\\r");
            case '\t' -> writer.write("\\t");
            default -> {
                if (character < 0x20) {
                    writer.write("\\u");
                    writer.write(HEX[(character >>> 12) & 0x0f]);
                    writer.write(HEX[(character >>> 8) & 0x0f]);
                    writer.write(HEX[(character >>> 4) & 0x0f]);
                    writer.write(HEX[character & 0x0f]);
                } else {
                    writer.write(character);
                }
            }
        }
    }

    private static final class SensitiveByteArrayOutputStream extends ByteArrayOutputStream {
        private byte[] snapshot() {
            return toByteArray();
        }

        @Override
        public void close() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    private JsonObject tokenPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("accessToken", accessToken);
        payload.addProperty("clientToken", clientToken);
        return payload;
    }

    private static String requireString(JsonObject object, String key) throws IOException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw new IOException("Authentication response is missing " + key);
        }
        try {
            String value = object.get(key).getAsString();
            if (value.isBlank()) {
                throw new IOException("Authentication response has blank " + key);
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Authentication response has invalid " + key, invalid);
        }
    }

    private static JsonObject parseResponseObject(String response, String operation) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonObject()) {
                throw new IOException("Yggdrasil " + operation + " response is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Yggdrasil " + operation + " response is invalid", error);
        }
    }
}
