package com.ecl.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Validates and atomically toggles local mod filenames without overwriting. */
final class ModFileToggle {
    private ModFileToggle() {
    }

    static Path safePath(Path directory, String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Mod filename is required");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(filename).normalize();
        if (!resolved.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid mod filename");
        }
        return resolved;
    }

    static void moveWithoutOverwrite(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("Refusing to overwrite existing mod: " + target.getFileName());
        }
        Files.move(source, target);
    }
}
