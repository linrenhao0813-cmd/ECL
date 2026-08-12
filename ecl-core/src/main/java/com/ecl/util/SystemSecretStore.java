package com.ecl.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Stores the account encryption key in the current user's native credential service. */
final class SystemSecretStore {
    private static final String SERVICE = "com.ecl.launcher.account-key";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);

    private SystemSecretStore() {
    }

    static void store(String identifier, byte[] secret) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(secret);
        switch (PlatformUtil.current()) {
            case MACOS -> run(List.of("/usr/bin/security", "add-generic-password", "-U",
                    "-a", identifier, "-s", SERVICE, "-w", encoded), null, "macOS Keychain");
            case LINUX -> run(List.of("secret-tool", "store", "--label=ECL account encryption key",
                    "application", SERVICE, "account", identifier), encoded, "Secret Service");
            default -> throw new IOException("No supported system credential store is available");
        }
    }

    static byte[] load(String identifier) throws IOException {
        String encoded = switch (PlatformUtil.current()) {
            case MACOS -> run(List.of("/usr/bin/security", "find-generic-password",
                    "-a", identifier, "-s", SERVICE, "-w"), null, "macOS Keychain");
            case LINUX -> run(List.of("secret-tool", "lookup", "application", SERVICE,
                    "account", identifier), null, "Secret Service");
            default -> throw new IOException("No supported system credential store is available");
        };
        try {
            return Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("System credential store returned an invalid account key", invalid);
        }
    }

    private static String run(List<String> command, String input, String displayName) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            throw new IOException(displayName + " is unavailable; account credentials cannot be stored safely",
                    unavailable);
        }
        try {
            if (input != null) {
                process.getOutputStream().write(input.getBytes(StandardCharsets.US_ASCII));
            }
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), output));
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(displayName + " did not respond in time");
            }
            reader.join(COMMAND_TIMEOUT.toMillis());
            String result = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException(displayName + " rejected the account key operation"
                        + (result.isBlank() ? "" : ": " + TextUtil.abbreviate(result, 200)));
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while accessing " + displayName, interrupted);
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // The command exit status provides the authoritative failure signal.
        }
    }
}
