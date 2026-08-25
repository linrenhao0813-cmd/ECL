package com.ecl.util;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Shared validation for remote download and browser-facing HTTP(S) addresses. */
public final class NetworkUriPolicy {
    private NetworkUriPolicy() {
    }

    /** Require HTTPS, except for an explicit numeric/localhost loopback HTTP endpoint. */
    public static URI requireHttpsOrLoopbackHttp(URI uri, String description) throws IOException {
        URI checked = requireNetworkUri(uri, description);
        String scheme = checked.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)
                || "http".equals(scheme) && isLoopbackHostLiteral(checked.getHost())) {
            return checked;
        }
        throw new IOException(description + " must use HTTPS (HTTP is allowed only for loopback)");
    }

    /** Require an ordinary HTTPS URL with no embedded credentials. */
    public static URI requireHttps(URI uri, String description) throws IOException {
        URI checked = requireNetworkUri(uri, description);
        if (!"https".equalsIgnoreCase(checked.getScheme())) {
            throw new IOException(description + " must use HTTPS");
        }
        return checked;
    }

    /** Require HTTPS/loopback HTTP and an exact host from the supplied allowlist. */
    public static URI requireAllowedDownload(
            URI uri, Set<String> allowedHosts, String description) throws IOException {
        URI checked = requireHttpsOrLoopbackHttp(uri, description);
        String host = checked.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts != null && allowedHosts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(host::equals);
        if (!allowed) {
            throw new IOException(description + " host is not trusted: " + host);
        }
        return checked;
    }

    /** Strict literal loopback recognition; hostnames such as 127.example.com are rejected. */
    public static boolean isLoopbackHostLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || "::1".equals(normalized)
                || "[::1]".equals(normalized)) {
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        return true;
    }

    private static URI requireNetworkUri(URI uri, String description) throws IOException {
        String label = description == null || description.isBlank() ? "URL" : description;
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IOException(label + " is invalid");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException(label + " has an unsupported scheme");
        }
        return uri;
    }
}
