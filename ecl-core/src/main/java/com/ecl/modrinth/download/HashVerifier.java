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
        HashResult actual = calculate(file);
        Map<String, String> expected = expectedHashes == null ? Map.of() : expectedHashes;
        verifyOne(file, "SHA-512", expected.get("sha512"), actual.sha512());
        verifyOne(file, "SHA-1", expected.get("sha1"), actual.sha1());
        return actual;
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
