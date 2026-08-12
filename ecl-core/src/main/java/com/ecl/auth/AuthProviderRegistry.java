package com.ecl.auth;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** Loads external auth factories while keeping built-in protocols always available. */
public final class AuthProviderRegistry {
    private final Map<AuthType, AuthProviderFactory> factories = new EnumMap<>(AuthType.class);

    public AuthProviderRegistry() {
        register(new BuiltInFactory(AuthType.OFFLINE));
        register(new BuiltInFactory(AuthType.MICROSOFT));
        register(new BuiltInFactory(AuthType.YGGDRASIL));
        ServiceLoader.load(AuthProviderFactory.class).forEach(this::register);
    }

    public void register(AuthProviderFactory factory) {
        if (factory != null) factories.put(factory.type(), factory);
    }

    public AuthProvider create(AuthAccount account) {
        AuthProviderFactory factory = factories.get(account.type());
        if (factory == null) throw new IllegalArgumentException("Unsupported auth type: " + account.type());
        return factory.create(account);
    }

    public List<String> providerIds() {
        List<String> ids = new ArrayList<>();
        factories.values().forEach(factory -> ids.add(factory.id()));
        return List.copyOf(ids);
    }

    private record BuiltInFactory(AuthType type) implements AuthProviderFactory {
        @Override
        public String id() {
            return "ecl-" + type.name().toLowerCase();
        }

        @Override
        public AuthProvider create(AuthAccount account) {
            return switch (type) {
                case OFFLINE -> new OfflineAuth(account.username());
                case MICROSOFT -> new MicrosoftAuth(new MicrosoftAuth.CachedSession(
                        account.refreshToken(), account.accessToken(), account.tokenExpiry(),
                        account.username(), account.uuid()), null);
                case YGGDRASIL -> new YggdrasilAuth(account.authServerUrl(), account.username(),
                        account.uuid(), account.accessToken(), account.refreshToken());
            };
        }
    }
}
