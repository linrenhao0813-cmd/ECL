package com.ecl.server;

import java.nio.file.Path;
import java.util.Objects;

/** Persisted settings for one isolated local dedicated server. */
public record LocalServerProfile(String id, String name, String javaPath, int memoryMb, boolean eulaAccepted) {
    public LocalServerProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javaPath, "javaPath");
        if (!id.matches("[a-f0-9-]{36}") || !java.util.UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid local server ID");
        }
        name = name.trim();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Server name must contain 1 to 100 printable characters");
        }
        if (javaPath.isBlank() || !Path.of(javaPath).isAbsolute()) {
            throw new IllegalArgumentException("Java executable must be an absolute path");
        }
        if (memoryMb < 256 || memoryMb > 65536) {
            throw new IllegalArgumentException("Server memory must be between 256 and 65536 MiB");
        }
    }
}
