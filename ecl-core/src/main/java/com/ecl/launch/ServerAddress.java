package com.ecl.launch;

/**
 * Parsed direct-connect server address. Understands {@code host}, {@code host:port} and
 * bracketed IPv6 addresses ({@code [::1]:25565}); a blank input parses as "no server".
 */
public record ServerAddress(String host, Integer port) {

    public static ServerAddress parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ServerAddress("", null);
        }
        String value = raw.trim();
        String host;
        Integer port = null;

        if (value.startsWith("[") && value.contains("]")) {
            int bracket = value.indexOf(']');
            host = value.substring(1, bracket);
            if (bracket + 1 < value.length() && value.charAt(bracket + 1) == ':') {
                port = tryPort(value.substring(bracket + 2));
            }
        } else {
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(':') == colon) {
                port = tryPort(value.substring(colon + 1));
                host = value.substring(0, colon);
            } else {
                host = value;
            }
        }
        return new ServerAddress(host, port);
    }

    private static Integer tryPort(String candidate) {
        if (candidate == null || !candidate.matches("\\d{1,5}")) {
            return null;
        }
        int value = Integer.parseInt(candidate);
        return value >= 1 && value <= 65_535 ? value : null;
    }

    /** Whether this represents an actual direct-connect target. */
    public boolean hasServer() {
        return host != null && !host.isBlank();
    }
}
