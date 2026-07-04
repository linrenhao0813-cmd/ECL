package com.ecl.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Microsoft account authentication for official Minecraft Java accounts.
 */
public class MicrosoftAuth implements AuthProvider {
    private static final Gson GSON = new Gson();

    private static final String CLIENT_ID = System.getProperty("ecl.microsoft.clientId", "00000000402b5328");
    private static final String MSA_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf?mkt=zh-CN";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private String username;
    private String uuid;
    private String accessToken;
    private String refreshToken;
    private boolean loggedIn;
    private final LoginListener listener;

    public MicrosoftAuth() {
        this(null, null);
    }

    public MicrosoftAuth(String refreshToken, LoginListener listener) {
        this.refreshToken = blankToNull(refreshToken);
        this.listener = listener;
    }

    @Override
    public String getUsername() {
        return username == null ? "MicrosoftUser" : username;
    }

    @Override
    public String getUUID() {
        return uuid == null ? "00000000000000000000000000000000" : uuid;
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    @Override
    public AuthType getType() {
        return AuthType.MICROSOFT;
    }

    @Override
    public boolean isLoggedIn() {
        return loggedIn;
    }

    @Override
    public void login() {
        try {
            authenticate();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Microsoft authentication was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Microsoft authentication failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void logout() {
        loggedIn = false;
        username = null;
        uuid = null;
        accessToken = null;
        refreshToken = null;
    }

    private void authenticate() throws IOException, InterruptedException {
        OAuthToken microsoftToken = tryRefreshMicrosoftToken();
        if (microsoftToken == null) {
            microsoftToken = loginWithDeviceCode();
        }

        if (microsoftToken.refreshToken != null && !microsoftToken.refreshToken.isBlank()) {
            refreshToken = microsoftToken.refreshToken;
        }

        notifyStatus("正在验证 Xbox Live 身份...");
        XboxToken xboxToken = authenticateXboxLive(microsoftToken.accessToken);

        notifyStatus("正在换取 Minecraft 服务令牌...");
        XboxToken xstsToken = authorizeXsts(xboxToken.token);
        String minecraftAccessToken = loginMinecraft(xstsToken.userHash, xstsToken.token);

        notifyStatus("正在检查 Minecraft Java 版授权...");
        validateEntitlements(minecraftAccessToken);
        JsonObject profile = loadMinecraftProfile(minecraftAccessToken);

        username = requireString(profile, "name", "Minecraft profile");
        uuid = requireString(profile, "id", "Minecraft profile");
        accessToken = minecraftAccessToken;
        loggedIn = true;
        notifyStatus("微软正版登录成功: " + username);
    }

    private OAuthToken tryRefreshMicrosoftToken() {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }

        notifyStatus("正在刷新微软登录状态...");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");
        form.put("scope", MSA_SCOPE);

        try {
            JsonObject json = requireSuccess(postForm(TOKEN_URL, form), "Microsoft refresh token");
            return parseOAuthToken(json);
        } catch (IOException e) {
            notifyStatus("已保存的微软登录已过期，需要重新授权。");
            return null;
        }
    }

    private OAuthToken loginWithDeviceCode() throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", MSA_SCOPE);
        form.put("response_type", "device_code");

        JsonObject json = requireSuccess(postForm(DEVICE_CODE_URL, form), "Microsoft device code");
        String deviceCode = requireString(json, "device_code", "Microsoft device code");
        String userCode = requireString(json, "user_code", "Microsoft device code");
        String verificationUri = getString(json, "verification_uri", getString(json, "verification_url", null));
        if (verificationUri == null || verificationUri.isBlank()) {
            throw new IOException("Microsoft device code response did not contain verification URL");
        }
        String message = getString(json, "message", "请在浏览器中打开 " + verificationUri + " 并输入代码 " + userCode);
        int expiresIn = Math.max(60, getInt(json, "expires_in", 900));
        int interval = Math.max(1, getInt(json, "interval", 5));

        DeviceCode prompt = new DeviceCode(deviceCode, userCode, verificationUri, message, expiresIn, interval);
        notifyDeviceCode(prompt);

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);

            Map<String, String> pollForm = new LinkedHashMap<>();
            pollForm.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            pollForm.put("client_id", CLIENT_ID);
            pollForm.put("device_code", deviceCode);

            HttpResponse response = postForm(TOKEN_URL, pollForm);
            JsonObject tokenJson = parseJson(response.body, "Microsoft token");
            if (response.isSuccess() && tokenJson.has("access_token")) {
                return parseOAuthToken(tokenJson);
            }

            String error = getString(tokenJson, "error", "");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }
            throw new IOException(errorMessage(tokenJson, "Microsoft device login failed"));
        }

        throw new IOException("Microsoft device login timed out");
    }

    private OAuthToken parseOAuthToken(JsonObject json) throws IOException {
        OAuthToken token = new OAuthToken();
        token.accessToken = requireString(json, "access_token", "Microsoft OAuth token");
        token.refreshToken = getString(json, "refresh_token", refreshToken);
        return token;
    }

    private XboxToken authenticateXboxLive(String microsoftAccessToken) throws IOException {
        try {
            return authenticateXboxLive(microsoftAccessToken, true);
        } catch (IOException first) {
            try {
                return authenticateXboxLive(microsoftAccessToken, false);
            } catch (IOException second) {
                first.addSuppressed(second);
                throw first;
            }
        }
    }

    private XboxToken authenticateXboxLive(String microsoftAccessToken, boolean prefixTicket) throws IOException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", prefixTicket ? "d=" + microsoftAccessToken : microsoftAccessToken);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        JsonObject json = requireSuccess(postJson(XBOX_AUTH_URL, payload), "Xbox Live authentication");
        return parseXboxToken(json, "Xbox Live authentication");
    }

    private XboxToken authorizeXsts(String xboxToken) throws IOException {
        JsonArray userTokens = new JsonArray();
        userTokens.add(xboxToken);

        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");

        HttpResponse response = postJson(XSTS_AUTH_URL, payload);
        JsonObject json = parseJson(response.body, "Xbox XSTS authorization");
        if (!response.isSuccess()) {
            throw new IOException(describeXstsError(json));
        }
        return parseXboxToken(json, "Xbox XSTS authorization");
    }

    private String loginMinecraft(String userHash, String xstsToken) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        JsonObject json = requireSuccess(postJson(MC_LOGIN_URL, payload), "Minecraft services login");
        return requireString(json, "access_token", "Minecraft services login");
    }

    private void validateEntitlements(String minecraftAccessToken) throws IOException {
        JsonObject json = requireSuccess(getBearer(MC_ENTITLEMENTS_URL, minecraftAccessToken), "Minecraft entitlement check");
        JsonArray items = json.has("items") && json.get("items").isJsonArray() ? json.getAsJsonArray("items") : null;
        if (items == null || items.isEmpty()) {
            throw new IOException("此微软账号没有 Minecraft Java 版授权。");
        }
    }

    private JsonObject loadMinecraftProfile(String minecraftAccessToken) throws IOException {
        HttpResponse response = getBearer(MC_PROFILE_URL, minecraftAccessToken);
        JsonObject json = parseJson(response.body, "Minecraft profile");
        if (response.isSuccess()) {
            return json;
        }

        if (response.code == 404) {
            throw new IOException("此微软账号没有可用的 Minecraft Java 版档案，请先购买并创建 Java 版玩家名。");
        }
        throw new IOException(errorMessage(json, "Minecraft profile request failed"));
    }

    private XboxToken parseXboxToken(JsonObject json, String source) throws IOException {
        XboxToken token = new XboxToken();
        token.token = requireString(json, "Token", source);

        if (json.has("DisplayClaims") && json.get("DisplayClaims").isJsonObject()) {
            JsonObject displayClaims = json.getAsJsonObject("DisplayClaims");
            if (displayClaims.has("xui") && displayClaims.get("xui").isJsonArray()) {
                JsonArray xui = displayClaims.getAsJsonArray("xui");
                if (!xui.isEmpty() && xui.get(0).isJsonObject()) {
                    token.userHash = getString(xui.get(0).getAsJsonObject(), "uhs", null);
                }
            }
        }

        if (token.userHash == null || token.userHash.isBlank()) {
            throw new IOException(source + " response did not contain user hash");
        }
        return token;
    }

    private HttpResponse postForm(String url, Map<String, String> form) throws IOException {
        return request("POST", url, "application/x-www-form-urlencoded", encodeForm(form), null);
    }

    private HttpResponse postJson(String url, JsonObject payload) throws IOException {
        return request("POST", url, "application/json", GSON.toJson(payload), null);
    }

    private HttpResponse getBearer(String url, String bearerToken) throws IOException {
        return request("GET", url, null, null, bearerToken);
    }

    private HttpResponse request(String method, String url, String contentType, String body, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "ECL/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }
        if (bearerToken != null && !bearerToken.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        String responseBody;
        try {
            responseBody = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } finally {
            conn.disconnect();
        }
        return new HttpResponse(code, responseBody);
    }

    private JsonObject requireSuccess(HttpResponse response, String source) throws IOException {
        JsonObject json = parseJson(response.body, source);
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
        }
        throw new IOException(source + " returned invalid JSON: " + abbreviate(body));
    }

    private String encodeForm(Map<String, String> form) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : form.entrySet()) {
            joiner.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }
        return joiner.toString();
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String requireString(JsonObject json, String key, String source) throws IOException {
        String value = getString(json, key, null);
        if (value == null || value.isBlank()) {
            throw new IOException(source + " response did not contain " + key);
        }
        return value;
    }

    private String getString(JsonObject json, String key, String defaultValue) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return defaultValue;
    }

    private int getInt(JsonObject json, String key, int defaultValue) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsInt();
        }
        return defaultValue;
    }

    private String errorMessage(JsonObject json, String fallback) {
        String description = getString(json, "error_description", null);
        if (description != null && !description.isBlank()) {
            return description;
        }
        String errorMessage = getString(json, "errorMessage", null);
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        String message = getString(json, "message", null);
        if (message != null && !message.isBlank()) {
            return message;
        }
        String error = getString(json, "error", null);
        return error == null || error.isBlank() ? fallback : error;
    }

    private String describeXstsError(JsonObject json) {
        String xerr = getString(json, "XErr", "");
        return switch (xerr) {
            case "2148916233" -> "此微软账号没有 Xbox 账号，请先在 xbox.com 创建 Xbox 档案。";
            case "2148916235" -> "Xbox Live 当前在该账号地区不可用。";
            case "2148916236" -> "此账号需要成人完成 Xbox 家庭安全验证。";
            case "2148916237" -> "此账号是儿童账号，暂不能完成 Xbox Live 登录。";
            case "2148916238" -> "此账号是儿童账号，需要加入家庭并由成人授权。";
            default -> errorMessage(json, "Xbox XSTS authorization failed");
        };
    }

    private void notifyDeviceCode(DeviceCode deviceCode) {
        if (listener != null) {
            listener.onDeviceCode(deviceCode);
        }
    }

    private void notifyStatus(String message) {
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String abbreviate(String text) {
        return text.length() > 240 ? text.substring(0, 237) + "..." : text;
    }

    public interface LoginListener {
        default void onDeviceCode(DeviceCode deviceCode) {
        }

        default void onStatus(String message) {
        }
    }

    public static class DeviceCode {
        private final String deviceCode;
        private final String userCode;
        private final String verificationUri;
        private final String message;
        private final int expiresIn;
        private final int interval;

        public DeviceCode(String deviceCode, String userCode, String verificationUri, String message, int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.message = message;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }

        public String getDeviceCode() {
            return deviceCode;
        }

        public String getUserCode() {
            return userCode;
        }

        public String getVerificationUri() {
            return verificationUri;
        }

        public String getMessage() {
            return message;
        }

        public int getExpiresIn() {
            return expiresIn;
        }

        public int getInterval() {
            return interval;
        }
    }

    private static class OAuthToken {
        private String accessToken;
        private String refreshToken;
    }

    private static class XboxToken {
        private String token;
        private String userHash;
    }

    private static class HttpResponse {
        private final int code;
        private final String body;

        private HttpResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        private boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }
}
