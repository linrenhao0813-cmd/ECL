package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.TextUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Performs Microsoft OAuth refresh-token and Device Code flows. */
final class MicrosoftOAuthClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftOAuthClient.class);
    private static final int MAX_DEVICE_POLL_INTERVAL_SECONDS = 60;
    private static final String CLIENT_ID =
            System.getProperty("ecl.microsoft.clientId", "00000000402b5328");
    private static final String MSA_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String DEVICE_CODE_URL =
            "https://login.live.com/oauth20_connect.srf?mkt=zh-CN";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private final AuthHttpTransport http;

    MicrosoftOAuthClient() {
        this(AuthHttpTransport.system());
    }

    MicrosoftOAuthClient(AuthHttpTransport http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    Token refresh(String refreshToken) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");
        form.put("scope", MSA_SCOPE);
        JsonObject json = requireSuccess(http.postForm(TOKEN_URL, form), "Microsoft refresh token");
        return parseToken(json, refreshToken);
    }

    Token loginWithDeviceCode(Listener listener) throws IOException, InterruptedException,
            MicrosoftAuth.LoginCancelledException {
        listener.ensureActive();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", MSA_SCOPE);
        form.put("response_type", "device_code");

        JsonObject json = requireSuccess(http.postForm(DEVICE_CODE_URL, form),
                "Microsoft device code");
        listener.ensureActive();
        String deviceCode = requireString(json, "device_code", "Microsoft device code");
        String userCode = requireString(json, "user_code", "Microsoft device code");
        String verificationUri = JsonUtil.getString(json, "verification_uri",
                JsonUtil.getString(json, "verification_url", null));
        if (verificationUri == null || verificationUri.isBlank()) {
            throw new IOException("Microsoft device code response did not contain verification URL");
        }
        String message = JsonUtil.getString(json, "message",
                "请在浏览器中打开 " + verificationUri + " 并输入代码 " + userCode);
        int expiresIn = Math.max(60, JsonUtil.getInt(json, "expires_in", 900));
        int interval = Math.min(MAX_DEVICE_POLL_INTERVAL_SECONDS,
                Math.max(1, JsonUtil.getInt(json, "interval", 5)));

        MicrosoftAuth.DeviceCode prompt = new MicrosoftAuth.DeviceCode(
                deviceCode, userCode, verificationUri, message, expiresIn, interval);
        listener.onDeviceCode(prompt);

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            listener.ensureActive();

            Map<String, String> pollForm = new LinkedHashMap<>();
            pollForm.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            pollForm.put("client_id", CLIENT_ID);
            pollForm.put("device_code", deviceCode);

            HttpUtil.Response response = http.postForm(TOKEN_URL, pollForm);
            listener.ensureActive();
            JsonObject tokenJson = parseJson(response.body(), "Microsoft token");
            if (response.isSuccess() && tokenJson.has("access_token")) {
                return parseToken(tokenJson, null);
            }

            String error = JsonUtil.getString(tokenJson, "error", "");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                interval = nextDevicePollInterval(interval);
                continue;
            }
            throw new IOException(errorMessage(tokenJson, "Microsoft device login failed"));
        }
        throw new IOException("Microsoft device login timed out");
    }

    static int nextDevicePollInterval(int currentInterval) {
        if (currentInterval >= MAX_DEVICE_POLL_INTERVAL_SECONDS) {
            return MAX_DEVICE_POLL_INTERVAL_SECONDS;
        }
        return Math.min(MAX_DEVICE_POLL_INTERVAL_SECONDS, Math.max(1, currentInterval) + 5);
    }

    private Token parseToken(JsonObject json, String fallbackRefreshToken) throws IOException {
        String accessToken = requireString(json, "access_token", "Microsoft OAuth token");
        String refreshToken = JsonUtil.getString(json, "refresh_token", fallbackRefreshToken);
        return new Token(accessToken, refreshToken);
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
        } catch (Exception error) {
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

    interface Listener {
        void ensureActive() throws MicrosoftAuth.LoginCancelledException;

        void onDeviceCode(MicrosoftAuth.DeviceCode deviceCode);
    }

    record Token(String accessToken, String refreshToken) {
    }
}
