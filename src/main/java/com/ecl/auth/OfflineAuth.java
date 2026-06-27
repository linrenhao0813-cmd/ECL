package com.ecl.auth;

import java.util.UUID;

public class OfflineAuth implements AuthProvider {
    private final String username;
    private final UUID uuid;

    public OfflineAuth(String username) {
        this.username = username;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getUUID() {
        return uuid.toString().replace("-", "");
    }

    @Override
    public String getAccessToken() {
        return "0";
    }

    @Override
    public AuthType getType() {
        return AuthType.OFFLINE;
    }

    @Override
    public boolean isLoggedIn() {
        return true;
    }

    @Override
    public void login() {
        // Offline auth is always "logged in"
    }

    @Override
    public void logout() {
        // No-op
    }
}
