package com.ecl.cli;

import com.ecl.auth.AuthProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Redacts credentials from CLI dry-run commands and environments. */
final class LaunchCommandSanitizer {
    private static final Set<String> SENSITIVE_OPTIONS = Set.of(
            "--accesstoken", "--access-token", "--session");

    private LaunchCommandSanitizer() {
    }

    static List<String> redactCommand(List<String> command, AuthProvider auth) {
        List<String> redacted = new ArrayList<>(command.size());
        boolean redactNext = false;
        for (String argument : command) {
            String lower = argument.toLowerCase(Locale.ROOT);
            if (redactNext) {
                redacted.add("<redacted>");
                redactNext = false;
            } else if (SENSITIVE_OPTIONS.contains(lower)) {
                redacted.add(argument);
                redactNext = true;
            } else if (SENSITIVE_OPTIONS.stream()
                    .anyMatch(option -> lower.startsWith(option + "="))) {
                redacted.add(argument.substring(0, argument.indexOf('=') + 1) + "<redacted>");
            } else {
                redacted.add(redactKnownSecret(argument, auth));
            }
        }
        return List.copyOf(redacted);
    }

    static Map<String, String> redactEnvironment(Map<String, String> environment,
                                                 AuthProvider auth) {
        Map<String, String> redacted = new LinkedHashMap<>();
        environment.forEach((key, value) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            redacted.put(key, lower.contains("token") || lower.contains("secret")
                    || lower.contains("password") || lower.contains("session")
                    ? "<redacted>" : redactKnownSecret(value, auth));
        });
        return Map.copyOf(redacted);
    }

    private static String redactKnownSecret(String value, AuthProvider auth) {
        String token = auth.getAccessToken();
        return token != null && !token.isBlank() && value != null && value.contains(token)
                ? value.replace(token, "<redacted>") : value;
    }
}
