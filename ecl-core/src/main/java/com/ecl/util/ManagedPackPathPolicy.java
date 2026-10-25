package com.ecl.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Lexical path checks independent of the launcher's working directory. */
public final class ManagedPackPathPolicy {
    private ManagedPackPathPolicy() { }

    public static Path requireRelativePath(String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.indexOf(':') >= 0) {
            throw new IOException(Messages.format("managedPack.invalidPath", value));
        }
        try {
            Path path = Path.of(value);
            for (Path segment : path) {
                String part = segment.toString();
                if (part.equals("..") || (!part.equals(".") && (part.endsWith(".") || part.endsWith(" ")))) {
                    throw new IOException(Messages.format("managedPack.invalidPath", value));
                }
            }
            path = path.normalize();
            if (path.isAbsolute() || path.toString().isEmpty() || path.toString().equals(".")) {
                throw new IOException(Messages.format("managedPack.invalidPath", value));
            }
            return path;
        } catch (IllegalArgumentException invalid) {
            throw new IOException(Messages.format("managedPack.invalidPath", value), invalid);
        }
    }

    public static Path requireManagedRelativePath(String value) throws IOException {
        Path path = requireRelativePath(value);
        String first = path.getName(0).toString().toLowerCase(Locale.ROOT);
        if (Set.of("saves", "screenshots", "logs", "crash-reports", ".ecl", "versions",
                "libraries", "assets", "servers.dat", "usercache.json").contains(first)) {
            throw new IOException(Messages.format("managedPack.userData", value));
        }
        return path;
    }
}
