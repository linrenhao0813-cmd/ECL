package com.ecl.modrinth.api;

import com.ecl.ECLConfig;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class ModrinthApiConfiguration {
    public static final URI BASE_URI = URI.create("https://api.modrinth.com/v2/");
    public static final String USER_AGENT = "ECL/" + ECLConfig.LAUNCHER_VERSION + " (Minecraft launcher)";

    private final URI baseUri;
    private final String userAgent;
    private final Duration requestTimeout;
    private final Duration cacheTtl;
    private final int maxAttempts;
    private final Duration initialRetryDelay;

    public ModrinthApiConfiguration(URI baseUri, String userAgent, Duration requestTimeout, Duration cacheTtl,
                                    int maxAttempts, Duration initialRetryDelay) {
        this.baseUri = ensureTrailingSlash(Objects.requireNonNull(baseUri, "baseUri"));
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
        this.userAgent = userAgent.trim();
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.cacheTtl = requirePositive(cacheTtl, "cacheTtl");
        if (maxAttempts < 1 || maxAttempts > 6) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 6");
        }
        this.maxAttempts = maxAttempts;
        this.initialRetryDelay = requirePositive(initialRetryDelay, "initialRetryDelay");
    }

    public static ModrinthApiConfiguration defaults() {
        return new ModrinthApiConfiguration(BASE_URI, USER_AGENT, Duration.ofSeconds(30),
                Duration.ofMinutes(2), 3, Duration.ofMillis(300));
    }

    private static URI ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? uri : URI.create(value + "/");
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public URI baseUri() {
        return baseUri;
    }

    public String userAgent() {
        return userAgent;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialRetryDelay() {
        return initialRetryDelay;
    }
}
