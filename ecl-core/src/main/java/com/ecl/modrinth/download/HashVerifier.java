package com.ecl.modrinth.download;

import com.ecl.modrinth.api.HashMismatchException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class HashVerifier {
    private static final int BUFFER_SIZE = 64 * 1024;

    public HashResult calculate(Path file) throws IOException {
        MessageDigest sha1 = digest("SHA-1");
        MessageDigest sha512 = digest("SHA-512");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Hash calculation cancelled");
                }
                sha1.update(buffer, 0, read);
                sha512.update(buffer, 0, read);
            }
        }
        return new HashResult(
                HexFormat.of().formatHex(sha1.digest()),
                HexFormat.of().formatHex(sha512.digest()));
    }

    public HashResult verify(Path file, Map<String, String> expectedHashes) throws IOException {
        Map<String, String> expected = expectedHashes == null ? Map.of() : expectedHashes;
        String expectedSha512 = normalizedExpected(expected.get("sha512"), 128, "SHA-512", file);
        String expectedSha1 = normalizedExpected(expected.get("sha1"), 40, "SHA-1", file);
        if (expectedSha512 == null && expectedSha1 == null) {
            throw new HashMismatchException(
                    "Downloaded file is missing a required SHA-512 or SHA-1 digest",
                    file, "SHA-512/SHA-1", "required", "missing");
        }
        HashResult actual = calculate(file);
        verifyOne(file, "SHA-512", expectedSha512, actual.sha512());
        verifyOne(file, "SHA-1", expectedSha1, actual.sha1());
        return actual;
    }

    public static boolean hasUsableExpectedHash(Map<String, String> expectedHashes) {
        Map<String, String> expected = expectedHashes == null ? Map.of() : expectedHashes;
        return matchesHex(expected.get("sha512"), 128) || matchesHex(expected.get("sha1"), 40);
    }

    private static String normalizedExpected(
            String value, int length, String algorithm, Path file) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!matchesHex(normalized, length)) {
            throw new HashMismatchException("Invalid expected " + algorithm + " digest",
                    file, algorithm, normalized, "invalid metadata");
        }
        return normalized;
    }

    private static boolean matchesHex(String value, int length) {
        return value != null && value.trim().matches("(?i)[0-9a-f]{" + length + "}");
    }

    private static void verifyOne(Path file, String algorithm, String expected, String actual) {
        if (expected != null && !expected.isBlank()
                && !expected.trim().toLowerCase(Locale.ROOT).equals(actual)) {
            throw new HashMismatchException(
                    algorithm + " mismatch for " + file.getFileName()
                            + ": expected " + expected + ", got " + actual,
                    file, algorithm, expected, actual);
        }
    }

    private static MessageDigest digest(String name) {
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(name + " is unavailable", e);
        }
    }

    public record HashResult(String sha1, String sha512) {
    }
}
