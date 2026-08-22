package com.ecl.auth;

/** Mutable Microsoft/Minecraft session data kept separate from the authentication flow. */
final class MicrosoftSessionState {
    private volatile String username;
    private volatile String uuid;
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;
    private volatile String refreshToken;
    private volatile boolean loggedIn;

    MicrosoftSessionState(MicrosoftAuth.CachedSession cachedSession) {
        this.refreshToken = blankToNull(cachedSession.refreshToken());
        this.accessToken = blankToNull(cachedSession.accessToken());
        this.accessTokenExpiresAt = cachedSession.accessTokenExpiresAt();
        this.username = blankToNull(cachedSession.username());
        this.uuid = blankToNull(cachedSession.uuid());
    }

    String usernameOrDefault() {
        return username == null ? "MicrosoftUser" : username;
    }

    String uuidOrDefault() {
        return uuid == null ? "00000000000000000000000000000000" : uuid;
    }

    String accessToken() {
        return accessToken;
    }

    String refreshToken() {
        return refreshToken;
    }

    long accessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    boolean loggedIn() {
        return loggedIn;
    }

    void clear() {
        loggedIn = false;
        username = null;
        uuid = null;
        accessToken = null;
        accessTokenExpiresAt = 0;
        refreshToken = null;
    }

    void clearAccessToken() {
        accessToken = null;
        accessTokenExpiresAt = 0;
        loggedIn = false;
    }

    void commitAuthenticated(String profileName, String profileUuid,
                             String minecraftAccessToken, long expiresAt) {
        username = profileName;
        uuid = profileUuid;
        accessToken = minecraftAccessToken;
        accessTokenExpiresAt = expiresAt;
        loggedIn = true;
    }

    void commitRefreshToken(String newRefreshToken) {
        refreshToken = newRefreshToken;
    }

    MicrosoftAuth.CachedSession snapshot() {
        return new MicrosoftAuth.CachedSession(
                refreshToken, accessToken, accessTokenExpiresAt, username, uuid);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
