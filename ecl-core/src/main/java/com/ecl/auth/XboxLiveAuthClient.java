package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.TextUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Performs Xbox Live authentication and XSTS authorization for Minecraft Services. */
final class XboxLiveAuthClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(XboxLiveAuthClient.class);
    private static final String XBOX_AUTH_URL =
            "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize";
    private final AuthHttpTransport http;

    XboxLiveAuthClient() {
        this(AuthHttpTransport.system());
    }

    XboxLiveAuthClient(AuthHttpTransport http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    Token authenticate(String microsoftAccessToken) throws IOException {
        try {
            return authenticate(microsoftAccessToken, true);
        } catch (IOException first) {
            try {
                return authenticate(microsoftAccessToken, false);
            } catch (IOException second) {
                first.addSuppressed(second);
                throw first;
            }
        }
    }

    Token authorizeXsts(String xboxToken) throws IOException {
        JsonArray userTokens = new JsonArray();
        userTokens.add(xboxToken);

        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");

        HttpUtil.Response response = http.postJson(XSTS_AUTH_URL, payload);
        JsonObject json = parseJson(response.body(), "Xbox XSTS authorization");
        if (!response.isSuccess()) {
            throw new IOException(describeXstsError(json));
        }
        return parseToken(json, "Xbox XSTS authorization");
    }

    private Token authenticate(String microsoftAccessToken, boolean prefixTicket)
            throws IOException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", prefixTicket
                ? "d=" + microsoftAccessToken : microsoftAccessToken);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        JsonObject json = requireSuccess(
                http.postJson(XBOX_AUTH_URL, payload), "Xbox Live authentication");
        return parseToken(json, "Xbox Live authentication");
    }

    private Token parseToken(JsonObject json, String source) throws IOException {
        String token = requireString(json, "Token", source);
        String userHash = null;
        if (json.has("DisplayClaims") && json.get("DisplayClaims").isJsonObject()) {
            JsonObject displayClaims = json.getAsJsonObject("DisplayClaims");
            if (displayClaims.has("xui") && displayClaims.get("xui").isJsonArray()) {
                JsonArray xui = displayClaims.getAsJsonArray("xui");
                if (!xui.isEmpty() && xui.get(0).isJsonObject()) {
                    userHash = JsonUtil.getString(xui.get(0).getAsJsonObject(), "uhs", null);
                }
            }
        }
        if (userHash == null || userHash.isBlank()) {
            throw new IOException(source + " response did not contain user hash");
        }
        return new Token(token, userHash);
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
        } catch (RuntimeException error) {
            LOGGER.warn("{} returned invalid JSON", source, error);
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

    private String describeXstsError(JsonObject json) {
        String xerr = JsonUtil.getString(json, "XErr", "");
        return switch (xerr) {
            case "2148916233" -> "此微软账号没有 Xbox 账号，请先在 xbox.com 创建 Xbox 档案。";
            case "2148916235" -> "Xbox Live 当前在该账号地区不可用。";
            case "2148916236" -> "此账号需要成人完成 Xbox 家庭安全验证。";
            case "2148916237" -> "此账号是儿童账号，暂不能完成 Xbox Live 登录。";
            case "2148916238" -> "此账号是儿童账号，需要加入家庭并由成人授权。";
            default -> errorMessage(json, "Xbox XSTS authorization failed");
        };
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

    record Token(String token, String userHash) {
    }
}
