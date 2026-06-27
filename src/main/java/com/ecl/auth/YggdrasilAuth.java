package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.UUID;

/**
 * Yggdrasil authentication for third-party authlib servers.
 * Compatible with LittleSkin, Blessing Skin, and other authlib-injector based servers.
 */
public class YggdrasilAuth implements AuthProvider {

    private final String authServer;
    private String username;
    private String password;
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
        this.password = password;
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
            throw new RuntimeException("Yggdrasil authentication failed", e);
        }
    }

    @Override
    public void logout() {
        if (accessToken != null) {
            try {
                String payload = "{\"accessToken\":\"" + accessToken + "\",\"clientToken\":\"" + clientToken + "\"}";
                postJson(authServer + "invalidate", payload);
            } catch (IOException ignored) {
            }
        }
        loggedIn = false;
        uuid = null;
        accessToken = null;
    }

    public void authenticate(String username, String password) throws IOException {
        String payload = "{"
                + "\"agent\":{\"name\":\"Minecraft\",\"version\":1},"
                + "\"username\":\"" + escape(username) + "\","
                + "\"password\":\"" + escape(password) + "\","
                + "\"clientToken\":\"" + clientToken + "\""
                + "}";

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
    }

    public boolean validate() throws IOException {
        if (accessToken == null) {
            return false;
        }

        String payload = "{\"accessToken\":\"" + accessToken + "\",\"clientToken\":\"" + clientToken + "\"}";
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

        String payload = "{\"accessToken\":\"" + accessToken + "\",\"clientToken\":\"" + clientToken + "\"}";
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

    private String postJson(String urlStr, String body) throws IOException {
        return HttpUtil.postJson(urlStr, body);
    }

    private String escape(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}