package com.ecl.auth;

public interface AuthProvider {
    String getUsername();
    String getUUID();
    String getAccessToken();
    AuthType getType();
    boolean isLoggedIn();
    void login();
    void logout();
}
