package com.ecl.util;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Creates and caches proxy-aware HTTP clients by connect timeout. */
final class HttpClientProvider {
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private static final HttpClient DEFAULT_CLIENT = create(
            DEFAULT_CONNECT_TIMEOUT_MS, HttpClient.Redirect.NEVER);
    private static final HttpClient DOWNLOAD_CLIENT = create(
            DEFAULT_CONNECT_TIMEOUT_MS, HttpClient.Redirect.NORMAL);
    private static final ConcurrentMap<Integer, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    private HttpClientProvider() {
    }

    static HttpClient defaultClient() {
        return DEFAULT_CLIENT;
    }

    /** Redirect-capable client for credential-free, integrity-checked file downloads only. */
    static HttpClient downloadClient() {
        return DOWNLOAD_CLIENT;
    }

    static HttpClient forConnectTimeout(int connectTimeoutMs) {
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (connectTimeoutMs == DEFAULT_CONNECT_TIMEOUT_MS) {
            return DEFAULT_CLIENT;
        }
        return CLIENTS.computeIfAbsent(connectTimeoutMs,
                timeout -> create(timeout, HttpClient.Redirect.NEVER));
    }

    static Optional<ProxySelector> proxySelectorFor(String httpsProxy, String httpProxy,
                                                     String allProxy) {
        for (String value : new String[]{httpsProxy, httpProxy, allProxy}) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                String normalized = value.contains("://") ? value : "http://" + value;
                URI uri = URI.create(normalized.trim());
                String host = uri.getHost();
                int port = uri.getPort();
                if (host != null && !host.isBlank() && port > 0 && port <= 65535) {
                    return Optional.of(ProxySelector.of(new InetSocketAddress(host, port)));
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next standard proxy variable.
            }
        }
        return Optional.empty();
    }

    private static HttpClient create(int connectTimeoutMs, HttpClient.Redirect redirectPolicy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(redirectPolicy)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs));
        proxySelectorFor(System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"),
                System.getenv("ALL_PROXY")).ifPresent(builder::proxy);
        return builder.build();
    }
}
