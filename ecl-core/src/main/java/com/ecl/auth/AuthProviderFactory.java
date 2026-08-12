package com.ecl.auth;

/** Service-provider extension point for account protocols. */
public interface AuthProviderFactory {
    String id();

    AuthType type();

    AuthProvider create(AuthAccount account);
}
