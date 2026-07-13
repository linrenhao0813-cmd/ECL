package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.TextUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Microsoft account authentication for official Minecraft Java accounts.
 */
public class MicrosoftAuth implements AuthProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftAuth.class);
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
    private long accessTokenExpiresAt;
    private String refreshToken;
    private boolean loggedIn;
    private final LoginListener listener;

    public MicrosoftAuth() {
        this(CachedSession.empty(), null);
    }

    public MicrosoftAuth(String refreshToken, LoginListener listener) {
        this(new CachedSession(refreshToken, null, 0, null, null), listener);
    }

    public MicrosoftAuth(CachedSession cachedSession, LoginListener listener) {
        CachedSession cached = cachedSession == null ? CachedSession.empty() : cachedSession;
        this.refreshToken = blankToNull(cached.refreshToken());
        this.accessToken = blankToNull(cached.accessToken());
        this.accessTokenExpiresAt = cached.accessTokenExpiresAt();
        this.username = blankToNull(cached.username());
        this.uuid = blankToNull(cached.uuid());
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

    /** Epoch milliseconds at which the cached Minecraft access token expires. */
    public long getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
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
        accessTokenExpiresAt = 0;
        refreshToken = null;
    }

    private void authenticate() throws IOException, InterruptedException {
        if (tryCachedMinecraftSession()) {
            return;
        }

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
        MinecraftToken minecraftToken = loginMinecraft(xstsToken.userHash, xstsToken.token);

        notifyStatus("正在检查 Minecraft Java 版授权...");
        validateEntitlements(minecraftToken.accessToken);
        JsonObject profile = loadMinecraftProfile(minecraftToken.accessToken);

        username = requireString(profile, "name", "Minecraft profile");
        uuid = requireString(profile, "id", "Minecraft profile");
        accessToken = minecraftToken.accessToken;
        accessTokenExpiresAt = minecraftToken.expiresAt;
        loggedIn = true;
        notifyStatus("微软正版登录成功: " + username);
    }

    private boolean tryCachedMinecraftSession() {
        long reuseDeadline = System.currentTimeMillis() + 2 * 60_000L;
        if (accessToken == null || accessTokenExpiresAt <= reuseDeadline) {
            accessToken = null;
            accessTokenExpiresAt = 0;
            return false;
        }

        notifyStatus("正在验证缓存的 Minecraft 登录状态...");
        try {
            validateEntitlements(accessToken);
            JsonObject profile = loadMinecraftProfile(accessToken);
            username = requireString(profile, "name", "Minecraft profile");
            uuid = requireString(profile, "id", "Minecraft profile");
            loggedIn = true;
            notifyStatus("已恢复 Minecraft 登录状态: " + username);
            return true;
        } catch (IOException e) {
            LOGGER.info("Cached Minecraft access token could not be reused; falling back to refresh token", e);
            accessToken = null;
            accessTokenExpiresAt = 0;
            loggedIn = false;
            notifyStatus("缓存登录已失效，正在静默刷新 Microsoft 登录状态...");
            return false;
        }
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
            JsonObject json = requireSuccess(HttpUtil.postForm(TOKEN_URL, form), "Microsoft refresh token");
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

        JsonObject json = requireSuccess(HttpUtil.postForm(DEVICE_CODE_URL, form), "Microsoft device code");
        String deviceCode = requireString(json, "device_code", "Microsoft device code");
        String userCode = requireString(json, "user_code", "Microsoft device code");
        String verificationUri = JsonUtil.getString(json, "verification_uri", JsonUtil.getString(json, "verification_url", null));
        if (verificationUri == null || verificationUri.isBlank()) {
            throw new IOException("Microsoft device code response did not contain verification URL");
        }
        String message = JsonUtil.getString(json, "message", "请在浏览器中打开 " + verificationUri + " 并输入代码 " + userCode);
        int expiresIn = Math.max(60, JsonUtil.getInt(json, "expires_in", 900));
        int interval = Math.max(1, JsonUtil.getInt(json, "interval", 5));

        DeviceCode prompt = new DeviceCode(deviceCode, userCode, verificationUri, message, expiresIn, interval);
        notifyDeviceCode(prompt);

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);

            Map<String, String> pollForm = new LinkedHashMap<>();
            pollForm.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            pollForm.put("client_id", CLIENT_ID);
            pollForm.put("device_code", deviceCode);

            HttpUtil.Response response = HttpUtil.postForm(TOKEN_URL, pollForm);
            JsonObject tokenJson = parseJson(response.body(), "Microsoft token");
            if (response.isSuccess() && tokenJson.has("access_token")) {
                return parseOAuthToken(tokenJson);
            }

            String error = JsonUtil.getString(tokenJson, "error", "");
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
        token.refreshToken = JsonUtil.getString(json, "refresh_token", refreshToken);
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

        JsonObject json = requireSuccess(HttpUtil.postJsonResponse(XBOX_AUTH_URL, payload), "Xbox Live authentication");
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

        HttpUtil.Response response = HttpUtil.postJsonResponse(XSTS_AUTH_URL, payload);
        JsonObject json = parseJson(response.body(), "Xbox XSTS authorization");
        if (!response.isSuccess()) {
            throw new IOException(describeXstsError(json));
        }
        return parseXboxToken(json, "Xbox XSTS authorization");
    }

    private MinecraftToken loginMinecraft(String userHash, String xstsToken) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        JsonObject json = requireSuccess(HttpUtil.postJsonResponse(MC_LOGIN_URL, payload), "Minecraft services login");
        MinecraftToken token = new MinecraftToken();
        token.accessToken = requireString(json, "access_token", "Minecraft services login");
        int expiresIn = Math.max(60, JsonUtil.getInt(json, "expires_in", 86_400));
        token.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        return token;
    }

    private void validateEntitlements(String minecraftAccessToken) throws IOException {
        JsonObject json = requireSuccess(HttpUtil.getBearer(MC_ENTITLEMENTS_URL, minecraftAccessToken), "Minecraft entitlement check");
        JsonArray items = json.has("items") && json.get("items").isJsonArray() ? json.getAsJsonArray("items") : null;
        if (items == null || items.isEmpty()) {
            throw new IOException("此微软账号没有 Minecraft Java 版授权。");
        }
    }

    private JsonObject loadMinecraftProfile(String minecraftAccessToken) throws IOException {
        HttpUtil.Response response = HttpUtil.getBearer(MC_PROFILE_URL, minecraftAccessToken);
        JsonObject json = parseJson(response.body(), "Minecraft profile");
        if (response.isSuccess()) {
            return json;
        }

        if (response.statusCode() == 404) {
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
                    token.userHash = JsonUtil.getString(xui.get(0).getAsJsonObject(), "uhs", null);
                }
            }
        }

        if (token.userHash == null || token.userHash.isBlank()) {
            throw new IOException(source + " response did not contain user hash");
        }
        return token;
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
        } catch (Exception e) {
            LOGGER.warn("{} returned invalid JSON", source, e);
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

    public interface LoginListener {
        default void onDeviceCode(DeviceCode deviceCode) {
        }

        default void onStatus(String message) {
        }
    }

    public record CachedSession(String refreshToken, String accessToken, long accessTokenExpiresAt,
                                String username, String uuid) {
        public static CachedSession empty() {
            return new CachedSession(null, null, 0, null, null);
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

    private static class MinecraftToken {
        private String accessToken;
        private long expiresAt;
    }

}
