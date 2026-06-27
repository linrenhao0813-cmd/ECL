package com.ecl.auth;

/**
 * Factory for creating AuthProvider instances.
 */
public class AuthFactory {

    public static AuthProvider createOffline(String username) {
        return new OfflineAuth(username);
    }

    public static AuthProvider createMicrosoft() {
        return new MicrosoftAuth();
    }

    public static AuthProvider createYggdrasil(String authServer) {
        return new YggdrasilAuth(authServer);
    }
}
