package com.ecl.auth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists Yggdrasil tokens without retaining the account password. */
public final class YggdrasilSessionStore {
    private final AccountService accounts;

    public YggdrasilSessionStore() {
        this(new DefaultAccountService());
    }

    public YggdrasilSessionStore(AccountService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    public Optional<YggdrasilAuth> restore(String authServer, String username) {
        String normalizedServer = YggdrasilAuth.normalizeAuthServer(authServer);
        return find(accounts.list(), normalizedServer, username)
                .map(account -> new YggdrasilAuth(normalizedServer, account.username(), account.uuid(),
                        account.accessToken(), account.refreshToken()));
    }

    public void save(String authServer, YggdrasilAuth auth) {
        Objects.requireNonNull(auth, "auth");
        String normalizedServer = YggdrasilAuth.normalizeAuthServer(authServer);
        List<AuthAccount> existingAccounts = accounts.list();
        Optional<AuthAccount> existing = find(existingAccounts, normalizedServer, auth.getUsername());
        boolean selected = existing.map(AuthAccount::defaultAccount)
                .orElseGet(() -> existingAccounts.isEmpty());
        accounts.save(new AuthAccount(AuthType.YGGDRASIL, auth.getUUID(), auth.getUsername(),
                auth.getUsername(), auth.getAccessToken(), auth.getClientToken(), 0,
                normalizedServer, selected));
    }

    private static Optional<AuthAccount> find(List<AuthAccount> accounts, String normalizedServer,
                                              String username) {
        String expectedUsername = username == null ? "" : username.trim();
        return accounts.stream()
                .filter(account -> account.type() == AuthType.YGGDRASIL)
                .filter(account -> account.authServerUrl().equalsIgnoreCase(normalizedServer))
                .filter(account -> account.username().equalsIgnoreCase(expectedUsername))
                .findFirst();
    }
}
