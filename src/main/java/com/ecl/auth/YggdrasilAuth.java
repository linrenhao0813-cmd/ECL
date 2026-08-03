package com.ecl.auth;

import com.ecl.exception.AuthException;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
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
        this.authServer = authServer.endsWith("/") ? authServer : authServer + "/";
        this.clientToken = UUID.randomUUID().toString().replace("-", "");
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
        // Convert char[] to String at the last possible moment
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
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();

            if (json.has("error")) {
                String message = json.has("errorMessage") ? json.get("errorMessage").getAsString() : json.get("error").getAsString();
                throw new IOException("Authentication failed: " + message);
            }

            this.accessToken = json.get("accessToken").getAsString();
            this.clientToken = json.get("clientToken").getAsString();

            JsonObject profile = json.getAsJsonObject("selectedProfile");
            if (profile == null && json.has("availableProfiles") && json.getAsJsonArray("availableProfiles").size() > 0) {
                profile = json.getAsJsonArray("availableProfiles").get(0).getAsJsonObject();
            }

            if (profile != null) {
                this.uuid = profile.get("id").getAsString();
                this.username = profile.get("name").getAsString();
            }

            this.loggedIn = true;

            // Clear password String immediately after use
            passwordStr = null;
        } finally {
            // Ensure password String is cleared even on exception
            if (passwordStr != null) {
                passwordStr = null;
            }
            clearPassword();
        }
    }

    public boolean validate() throws IOException {
        if (accessToken == null) {
            return false;
        }

        JsonObject payload = tokenPayload();
        try {
            postJson(authServer + "validate", payload);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void refresh() throws IOException {
        if (accessToken == null) {
            throw new IllegalStateException("No access token to refresh");
        }

        JsonObject payload = tokenPayload();
        String response = postJson(authServer + "refresh", payload);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        if (json.has("accessToken")) {
            this.accessToken = json.get("accessToken").getAsString();
        }
        if (json.has("selectedProfile")) {
            JsonObject profile = json.getAsJsonObject("selectedProfile");
            this.uuid = profile.get("id").getAsString();
            this.username = profile.get("name").getAsString();
        }
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
}
