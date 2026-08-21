package com.ecl.cli;

import com.ecl.auth.AuthAccount;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.AuthType;
import com.ecl.auth.DefaultAccountService;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.YggdrasilAuth;
import com.ecl.exception.AuthException;

import java.io.IOException;

/** Selects and refreshes the account used by the launch command. */
final class CliAccountAuthenticator {
    private CliAccountAuthenticator() {
    }

    static AuthProvider select(String identity, String fallbackName, boolean authenticate)
            throws IOException {
        DefaultAccountService accounts = new DefaultAccountService();
        AuthAccount account = identity == null || identity.isBlank()
                ? accounts.defaultAccount().orElse(null)
                : accounts.list().stream()
                .filter(item -> item.identity().equalsIgnoreCase(identity))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + identity));
        if (account == null || account.type() == AuthType.OFFLINE) {
            return account == null ? new OfflineAuth(fallbackName)
                    : accounts.createProvider(account);
        }
        if (!authenticate) {
            return accounts.createProvider(account);
        }
        if (account.type() == AuthType.MICROSOFT) {
            return authenticateMicrosoft(accounts, account);
        }
        return authenticateYggdrasil(accounts, account);
    }

    private static AuthProvider authenticateMicrosoft(
            DefaultAccountService accounts, AuthAccount account) throws IOException {
        MicrosoftAuth provider = new MicrosoftAuth(new MicrosoftAuth.CachedSession(
                account.refreshToken(), account.accessToken(), account.tokenExpiry(),
                account.username(), account.uuid()), new MicrosoftAuth.LoginListener() {
            @Override
            public void onDeviceCode(MicrosoftAuth.DeviceCode code) {
                System.err.println("Microsoft authorization: " + code.getVerificationUri()
                        + " code " + code.getUserCode());
            }

            @Override
            public void onStatus(String message) {
                System.err.println(message);
            }
        });
        provider.login();
        MicrosoftAuth.CachedSession session = provider.getCachedSession();
        accounts.save(new AuthAccount(account.type(), session.uuid(), session.username(),
                session.username(), session.accessToken(), session.refreshToken(),
                session.accessTokenExpiresAt(), account.authServerUrl(), account.defaultAccount()));
        return provider;
    }

    private static AuthProvider authenticateYggdrasil(
            DefaultAccountService accounts, AuthAccount account) throws IOException {
        YggdrasilAuth provider = (YggdrasilAuth) accounts.createProvider(account);
        if (!provider.validate()) {
            throw new AuthException(
                    "Saved Yggdrasil session is invalid; log in again before launch");
        }
        provider.refresh();
        accounts.save(new AuthAccount(account.type(), provider.getUUID(), provider.getUsername(),
                provider.getUsername(), provider.getAccessToken(), provider.getClientToken(),
                account.tokenExpiry(), account.authServerUrl(), account.defaultAccount()));
        return provider;
    }
}
