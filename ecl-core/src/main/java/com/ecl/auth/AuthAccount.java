package com.ecl.auth;

/** Persistable account model used by launcher authentication services. */
public record AuthAccount(
        AuthType type,
        String uuid,
        String username,
        String displayName,
        String accessToken,
        String refreshToken,
        long tokenExpiry,
        String authServerUrl,
        boolean defaultAccount) {

    public AuthAccount {
        type = type == null ? AuthType.OFFLINE : type;
        uuid = text(uuid);
        username = text(username);
        displayName = text(displayName).isBlank() ? username : displayName;
        accessToken = text(accessToken);
        refreshToken = text(refreshToken);
        authServerUrl = text(authServerUrl);
    }

    public String identity() {
        return type.name() + ":" + (uuid.isBlank() ? username.toLowerCase() : uuid.toLowerCase());
    }

    public AuthAccount withoutSecrets() {
        return new AuthAccount(type, uuid, username, displayName, "", "", tokenExpiry,
                authServerUrl, defaultAccount);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
