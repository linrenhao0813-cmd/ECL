package com.ecl.auth;

import com.ecl.exception.AuthException;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Yggdrasil authentication for third-party authlib servers.
 * Compatible with LittleSkin, Blessing Skin, and other authlib-injector based servers.
 */
public class YggdrasilAuth implements AuthProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(YggdrasilAuth.class);

    private final String authServer;
    private String username;
    private char[] password;
    private String uuid;
    private String accessToken;
    private String clientToken;
    private boolean loggedIn;

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
        this.username = username;
        this.password = password == null ? null : password.toCharArray();
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
        String passwordStr = new String(password);
        try {
            JsonObject agent = new JsonObject();
            agent.addProperty("name", "Minecraft");
            agent.addProperty("version", 1);
            JsonObject payload = new JsonObject();
            payload.add("agent", agent);
            payload.addProperty("username", username);
            payload.addProperty("password", passwordStr);
            payload.addProperty("clientToken", clientToken);

            String response = postJson(authServer + "authenticate", payload);
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
            clearPassword();
        }
    }

    public boolean validate() throws IOException {
        if (accessToken == null) {
            return false;
        }

        HttpUtil.Response response = HttpUtil.postJsonResponse(
                authServer + "validate", tokenPayload());
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
        return HttpUtil.postJson(urlStr, body);
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
