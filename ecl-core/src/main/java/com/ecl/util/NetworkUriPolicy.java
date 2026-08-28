package com.ecl.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Shared validation for remote download and browser-facing HTTP(S) addresses. */
public final class NetworkUriPolicy {
    private static final InheritableThreadLocal<Boolean> LOOPBACK_ARTIFACT_TEST_MODE =
            new InheritableThreadLocal<>();

    private NetworkUriPolicy() {
    }

    /**
     * Require HTTPS and reject every address range that can identify this host or private
     * networks. This is the policy for artifact downloads; unlike the loopback exception below,
     * it resolves host names before allowing a request.
     */
    public static URI requireSecureDownload(URI uri, String description) throws IOException {
        URI checked = requireNetworkUri(uri, description);
        if (!"https".equalsIgnoreCase(checked.getScheme())) {
            throw new IOException(label(description) + " must use HTTPS");
        }
        rejectUnsafeResolvedAddresses(checked, description);
        return checked;
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
        return requireSecureDownload(uri, description);
    }

    /** HTTP client policy used by API test/local endpoints and remote HTTPS services. */
    public static URI requireHttpRequest(URI uri, String description) throws IOException {
        URI checked = requireHttpsOrLoopbackHttp(uri, description);
        if ("https".equalsIgnoreCase(checked.getScheme())) {
            rejectUnsafeResolvedAddresses(checked, description);
        }
        return checked;
    }

    /** Strict production artifact policy; loopback HTTP is available only to scoped tests. */
    public static URI requireArtifactDownload(URI uri, String description) throws IOException {
        if (loopbackArtifactTestMode() && isLoopbackHttp(uri)) {
            return requireHttpRequest(uri, description);
        }
        return requireSecureDownload(uri, description);
    }

    /** Require HTTPS/loopback HTTP and an exact host from the supplied allowlist. */
    public static URI requireAllowedDownload(
            URI uri, Set<String> allowedHosts, String description) throws IOException {
        URI checked = loopbackArtifactTestMode() && isLoopbackHttp(uri)
                ? requireHttpRequest(uri, description)
                : requireSecureDownload(uri, description);
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

    static AutoCloseable allowLoopbackArtifactDownloadsForTesting() {
        Boolean previous = LOOPBACK_ARTIFACT_TEST_MODE.get();
        LOOPBACK_ARTIFACT_TEST_MODE.set(Boolean.TRUE);
        return () -> {
            if (previous == null) {
                LOOPBACK_ARTIFACT_TEST_MODE.remove();
            } else {
                LOOPBACK_ARTIFACT_TEST_MODE.set(previous);
            }
        };
    }

    private static boolean loopbackArtifactTestMode() {
        return Boolean.TRUE.equals(LOOPBACK_ARTIFACT_TEST_MODE.get());
    }

    private static boolean isLoopbackHttp(URI uri) {
        return uri != null && "http".equalsIgnoreCase(uri.getScheme())
                && isLoopbackHostLiteral(uri.getHost());
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
        String label = label(description);
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

    private static String label(String description) {
        return description == null || description.isBlank() ? "URL" : description;
    }

    private static void rejectUnsafeResolvedAddresses(URI uri, String description) throws IOException {
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (java.net.UnknownHostException error) {
            throw new IOException(label(description) + " host cannot be resolved", error);
        }
        if (addresses.length == 0) {
            throw new IOException(label(description) + " host has no address");
        }
        for (InetAddress address : addresses) {
            if (isUnsafeAddress(address)) {
                throw new IOException(label(description) + " resolves to a non-public address");
            }
        }
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? isUnsafeIpv4(bytes) : isUnsafeIpv6(bytes);
    }

    private static boolean isUnsafeIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        // RFC 6598 shared CGNAT space (100.64.0.0/10).
        if (first == 100 && second >= 64 && second <= 127) return true;
        // 0.0.0.0/8 and 169.254/16 are not consistently covered by all providers.
        return first == 0 || first == 169 && second == 254;
    }

    private static boolean isUnsafeIpv6(byte[] bytes) {
        // IPv6 unique-local addresses fc00::/7 and IPv4-mapped private addresses.
        int first = bytes[0] & 0xff;
        if ((first & 0xfe) == 0xfc) return true;
        if (!isIpv4Mapped(bytes)) return false;
        try {
            return isUnsafeAddress(InetAddress.getByAddress(
                    java.util.Arrays.copyOfRange(bytes, 12, 16)));
        } catch (java.net.UnknownHostException impossible) {
            return true;
        }
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
