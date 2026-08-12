package com.ecl.auth;

import java.util.List;
import java.util.Optional;

public interface AccountService {
    List<AuthAccount> list();

    AuthAccount save(AuthAccount account);

    AuthAccount addOffline(String username);

    boolean remove(String identity);

    Optional<AuthAccount> defaultAccount();

    void setDefault(String identity);

    AuthProvider createProvider(AuthAccount account);
}
