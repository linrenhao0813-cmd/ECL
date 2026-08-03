package com.ecl.modrinth.api;

import java.nio.file.Path;

public final class HashMismatchException extends RuntimeException {
    private final Path file;
    private final String algorithm;
    private final String expected;
    private final String actual;

    public HashMismatchException(String message) {
        this(message, null, "", "", "");
    }

    public HashMismatchException(
            String message,
            Path file,
            String algorithm,
            String expected,
            String actual
    ) {
        super(message);
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.algorithm = text(algorithm);
        this.expected = text(expected);
        this.actual = text(actual);
    }

    public Path file() {
        return file;
    }

    public String algorithm() {
        return algorithm;
    }

    public String expected() {
        return expected;
    }

    public String actual() {
        return actual;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
