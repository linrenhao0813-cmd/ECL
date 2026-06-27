package com.ecl.auth;

/**
 * Microsoft authentication provider (stub - not yet implemented).
 */
public class MicrosoftAuth implements AuthProvider {

    @Override
    public String getUsername() {
        return "MicrosoftUser";
    }

    @Override
    public String getUUID() {
        return "00000000000000000000000000000000";
    }

    @Override
    public String getAccessToken() {
        return null;
    }

    @Override
    public AuthType getType() {
        return AuthType.MICROSOFT;
    }

    @Override
    public boolean isLoggedIn() {
        return false;
    }

    @Override
    public void login() {
        throw new UnsupportedOperationException("Microsoft authentication not yet implemented");
    }

    @Override
    public void logout() {
        // No-op
    }
}
