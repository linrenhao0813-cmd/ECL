package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.TextUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/** Client for Minecraft Services authentication, entitlements and player profiles. */
final class MinecraftServicesClient {
    private static final String LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String ENTITLEMENTS_URL =
            "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";
    private final AuthHttpTransport http;

    MinecraftServicesClient() {
        this(AuthHttpTransport.system());
    }

    MinecraftServicesClient(AuthHttpTransport http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    Token loginWithXbox(String userHash, String xstsToken) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        JsonObject json = requireSuccess(
                http.postJson(LOGIN_URL, payload), "Minecraft services login");
        String accessToken = requireString(json, "access_token", "Minecraft services login");
        int expiresIn = Math.max(60, JsonUtil.getInt(json, "expires_in", 86_400));
        return new Token(accessToken, System.currentTimeMillis() + expiresIn * 1000L);
    }

    void validateEntitlements(String minecraftAccessToken) throws IOException {
        JsonObject json = requireSuccess(
                http.getBearer(ENTITLEMENTS_URL, minecraftAccessToken),
                "Minecraft entitlement check");
        JsonArray items = json.has("items") && json.get("items").isJsonArray()
                ? json.getAsJsonArray("items") : null;
        if (items == null || items.isEmpty()) {
            throw new IOException("此微软账号没有 Minecraft Java 版授权。");
        }
    }

    Profile loadProfile(String minecraftAccessToken) throws IOException {
        HttpUtil.Response response = http.getBearer(PROFILE_URL, minecraftAccessToken);
        JsonObject json = parseJson(response.body(), "Minecraft profile");
        if (!response.isSuccess()) {
            if (response.statusCode() == 404) {
                throw new IOException("此微软账号没有可用的 Minecraft Java 版档案，请先购买并创建 Java 版玩家名。");
            }
            throw new IOException(errorMessage(json, "Minecraft profile request failed"));
        }
        return new Profile(
                requireString(json, "name", "Minecraft profile"),
                requireString(json, "id", "Minecraft profile"));
    }

    private JsonObject requireSuccess(HttpUtil.Response response, String source) throws IOException {
        JsonObject json = parseJson(response.body(), source);
        if (response.isSuccess()) {
            return json;
        }
        throw new IOException(errorMessage(json, source + " failed"));
    }

    private JsonObject parseJson(String body, String source) throws IOException {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // Convert malformed responses to the same IOException contract below.
        }
        throw new IOException(source + " returned invalid JSON: " + TextUtil.abbreviate(body, 240));
    }

    private String requireString(JsonObject json, String key, String source) throws IOException {
        String value = JsonUtil.getString(json, key, null);
        if (value == null || value.isBlank()) {
            throw new IOException(source + " response did not contain " + key);
        }
        return value;
    }

    private String errorMessage(JsonObject json, String fallback) {
        String description = JsonUtil.getString(json, "error_description", null);
        if (description != null && !description.isBlank()) {
            return description;
        }
        String errorMessage = JsonUtil.getString(json, "errorMessage", null);
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        String message = JsonUtil.getString(json, "message", null);
        if (message != null && !message.isBlank()) {
            return message;
        }
        String error = JsonUtil.getString(json, "error", null);
        return error == null || error.isBlank() ? fallback : error;
    }

    record Token(String accessToken, long expiresAt) {
    }

    record Profile(String name, String uuid) {
    }
}
