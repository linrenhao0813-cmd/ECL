package com.ecl.auth;

import com.ecl.exception.AuthException;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Microsoft account authentication for official Minecraft Java accounts.
 */
public class MicrosoftAuth implements AuthProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftAuth.class);
    private final MinecraftServicesClient minecraftServicesClient;
    private final XboxLiveAuthClient xboxLiveAuthClient;
    private final MicrosoftOAuthClient oauthClient;

    private final MicrosoftSessionState sessionState;
    private final LoginListener listener;

    /** Lock for coordinating state field updates across authenticate/logout/getCachedSession. */
    private final Object stateLock = new Object();

    /**
     * 登录代次计数器。每次开始登录或 logout() 时递增，后台登录流程在写回状态前检查代次是否匹配，
     * 防止登录完成后才被 logout() 清空的状态被后台线程"复活"。
     */
    private long generation;

    public MicrosoftAuth() {
        this(CachedSession.empty(), null);
    }

    public MicrosoftAuth(String refreshToken, LoginListener listener) {
        this(new CachedSession(refreshToken, null, 0, null, null), listener);
    }

    public MicrosoftAuth(CachedSession cachedSession, LoginListener listener) {
        this(cachedSession, listener, new MinecraftServicesClient(),
                new XboxLiveAuthClient(), new MicrosoftOAuthClient());
    }

    MicrosoftAuth(CachedSession cachedSession, LoginListener listener,
                  MinecraftServicesClient minecraftServicesClient,
                  XboxLiveAuthClient xboxLiveAuthClient,
                  MicrosoftOAuthClient oauthClient) {
        CachedSession cached = cachedSession == null ? CachedSession.empty() : cachedSession;
        this.sessionState = new MicrosoftSessionState(cached);
        this.listener = listener;
        this.minecraftServicesClient = java.util.Objects.requireNonNull(
                minecraftServicesClient, "minecraftServicesClient");
        this.xboxLiveAuthClient = java.util.Objects.requireNonNull(
                xboxLiveAuthClient, "xboxLiveAuthClient");
        this.oauthClient = java.util.Objects.requireNonNull(oauthClient, "oauthClient");
    }

    @Override
    public String getUsername() {
        return sessionState.usernameOrDefault();
    }

    @Override
    public String getUUID() {
        return sessionState.uuidOrDefault();
    }

    @Override
    public String getAccessToken() {
        return sessionState.accessToken();
    }

    public String getRefreshToken() {
        return sessionState.refreshToken();
    }

    /** Epoch milliseconds at which the cached Minecraft access token expires. */
    public long getAccessTokenExpiresAt() {
        return sessionState.accessTokenExpiresAt();
    }

    @Override
    public AuthType getType() {
        return AuthType.MICROSOFT;
    }

    @Override
    public boolean isLoggedIn() {
        return sessionState.loggedIn();
    }

    @Override
    public void login() {
        long gen = beginLogin();
        try {
            authenticate(gen);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Microsoft authentication was interrupted", e);
        } catch (IOException e) {
            throw new AuthException("Microsoft authentication failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void logout() {
        synchronized (stateLock) {
            generation++;
            sessionState.clear();
        }
    }

    public CachedSession getCachedSession() {
        synchronized (stateLock) {
            return sessionState.snapshot();
        }
    }

    private void authenticate(long gen) throws IOException, InterruptedException {
        ensureActiveGeneration(gen);
        if (tryCachedMinecraftSession(gen)) {
            return;
        }

        MicrosoftOAuthClient.Token microsoftToken = tryRefreshMicrosoftToken(gen);
        if (microsoftToken == null) {
            microsoftToken = loginWithDeviceCode(gen);
        }

        ensureActiveGeneration(gen);
        notifyStatus("正在验证 Xbox Live 身份...");
        XboxLiveAuthClient.Token xboxToken = xboxLiveAuthClient.authenticate(microsoftToken.accessToken());

        ensureActiveGeneration(gen);
        notifyStatus("正在换取 Minecraft 服务令牌...");
        XboxLiveAuthClient.Token xstsToken = xboxLiveAuthClient.authorizeXsts(xboxToken.token());
        ensureActiveGeneration(gen);
        MinecraftServicesClient.Token minecraftToken =
                minecraftServicesClient.loginWithXbox(xstsToken.userHash(), xstsToken.token());

        ensureActiveGeneration(gen);
        notifyStatus("正在检查 Minecraft Java 版授权...");
        minecraftServicesClient.validateEntitlements(minecraftToken.accessToken());
        MinecraftServicesClient.Profile profile =
                minecraftServicesClient.loadProfile(minecraftToken.accessToken());

        String profileName = profile.name();
        String profileUuid = profile.uuid();
        commitAuthenticatedSession(gen, profileName, profileUuid,
                minecraftToken.accessToken(), minecraftToken.expiresAt(), microsoftToken.refreshToken());
        notifyStatusIfCurrent(gen, "微软正版登录成功: " + profileName);
        ensureActiveGeneration(gen);
    }

    private boolean tryCachedMinecraftSession(long gen) throws LoginCancelledException {
        long reuseDeadline = System.currentTimeMillis() + 2 * 60_000L;
        String cachedAccessToken;
        long cachedExpiresAt;
        synchronized (stateLock) {
            ensureActiveGenerationLocked(gen);
            cachedAccessToken = sessionState.accessToken();
            cachedExpiresAt = sessionState.accessTokenExpiresAt();
            if (cachedAccessToken == null || cachedExpiresAt <= reuseDeadline) {
                sessionState.clearAccessToken();
                return false;
            }
        }

        notifyStatus("正在验证缓存的 Minecraft 登录状态...");
        try {
            minecraftServicesClient.validateEntitlements(cachedAccessToken);
            MinecraftServicesClient.Profile profile =
                    minecraftServicesClient.loadProfile(cachedAccessToken);
            String profileName = profile.name();
            String profileUuid = profile.uuid();
            commitAuthenticatedSession(gen, profileName, profileUuid,
                    cachedAccessToken, cachedExpiresAt);
            notifyStatusIfCurrent(gen, "已恢复 Minecraft 登录状态: " + profileName);
            ensureActiveGeneration(gen);
            return true;
        } catch (LoginCancelledException e) {
            throw e;
        } catch (IOException e) {
            LOGGER.info("Cached Minecraft access token could not be reused; falling back to refresh token", e);
            synchronized (stateLock) {
                ensureActiveGenerationLocked(gen);
                sessionState.clearAccessToken();
            }
            notifyStatus("缓存登录已失效，正在静默刷新 Microsoft 登录状态...");
            return false;
        }
    }

    private MicrosoftOAuthClient.Token tryRefreshMicrosoftToken(long gen)
            throws LoginCancelledException {
        String cachedRefreshToken;
        synchronized (stateLock) {
            ensureActiveGenerationLocked(gen);
            cachedRefreshToken = sessionState.refreshToken();
            if (cachedRefreshToken == null || cachedRefreshToken.isBlank()) {
                return null;
            }
        }

        notifyStatus("正在刷新微软登录状态...");
        try {
            MicrosoftOAuthClient.Token token = oauthClient.refresh(cachedRefreshToken);
            ensureActiveGeneration(gen);
            return token;
        } catch (LoginCancelledException e) {
            throw e;
        } catch (IOException e) {
            notifyStatus("已保存的微软登录已过期，需要重新授权。");
            return null;
        }
    }

    private MicrosoftOAuthClient.Token loginWithDeviceCode(long gen)
            throws IOException, InterruptedException, LoginCancelledException {
        return oauthClient.loginWithDeviceCode(new MicrosoftOAuthClient.Listener() {
            @Override
            public void ensureActive() throws LoginCancelledException {
                MicrosoftAuth.this.ensureActiveGeneration(gen);
            }

            @Override
            public void onDeviceCode(DeviceCode deviceCode) {
                notifyDeviceCode(deviceCode);
            }
        });
    }

    static int nextDevicePollInterval(int currentInterval) {
        return MicrosoftOAuthClient.nextDevicePollInterval(currentInterval);
    }

    long beginLogin() {
        synchronized (stateLock) {
            return ++generation;
        }
    }

    void commitAuthenticatedSession(long gen, String profileName, String profileUuid,
                                    String minecraftAccessToken, long expiresAt)
            throws LoginCancelledException {
        synchronized (stateLock) {
            ensureActiveGenerationLocked(gen);
            sessionState.commitAuthenticated(profileName, profileUuid, minecraftAccessToken, expiresAt);
        }
    }

    private void commitAuthenticatedSession(long gen, String profileName, String profileUuid,
                                            String minecraftAccessToken, long expiresAt,
                                            String refreshToken) throws LoginCancelledException {
        synchronized (stateLock) {
            ensureActiveGenerationLocked(gen);
            sessionState.commitAuthenticated(profileName, profileUuid, minecraftAccessToken, expiresAt);
            if (refreshToken != null && !refreshToken.isBlank()) {
                sessionState.commitRefreshToken(refreshToken);
            }
        }
    }

    private void ensureActiveGeneration(long gen) throws LoginCancelledException {
        synchronized (stateLock) {
            ensureActiveGenerationLocked(gen);
        }
    }

    private void ensureActiveGenerationLocked(long gen) throws LoginCancelledException {
        if (generation != gen) {
            throw new LoginCancelledException();
        }
    }

    private void notifyStatusIfCurrent(long gen, String message) throws LoginCancelledException {
        ensureActiveGeneration(gen);
        notifyStatus(message);
        ensureActiveGeneration(gen);
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

    public interface LoginListener {
        default void onDeviceCode(DeviceCode deviceCode) {
        }

        default void onStatus(String message) {
        }
    }

    static final class LoginCancelledException extends IOException {
        LoginCancelledException() {
            super("Microsoft authentication was cancelled");
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

}
