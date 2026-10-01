package com.ecl.server;

import com.ecl.util.FileUtil;
import com.ecl.util.GsonProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Bounded, atomic persistence and managed-path checks shared by server operations. */
final class LocalServerFiles {
    private static final int MAX_JSON_BYTES = 16384;

    private LocalServerFiles() { }

    static Path safe(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        FileUtil.validateExistingAncestors(normalized.getRoot(), normalized);
        return normalized;
    }

    static <T> T read(Path file, Class<T> type) throws IOException {
        safe(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_JSON_BYTES) {
            throw new IOException("Missing or oversized server metadata: " + file);
        }
        try {
            T value = GsonProvider.pretty().fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (value == null) throw new IOException("Empty server metadata: " + file);
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid server metadata: " + file, invalid);
        }
    }

    static void write(Path file, Object value) throws IOException {
        writeText(file, GsonProvider.pretty().toJson(value));
    }

    static void writeText(Path file, String text) throws IOException {
        safe(file);
        Path temporary = Files.createTempFile(file.getParent(), ".ecl-server-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            safe(file);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
