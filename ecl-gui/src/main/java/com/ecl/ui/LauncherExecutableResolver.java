package com.ecl.ui;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves the packaged ECL executable used when creating shortcuts. */
final class LauncherExecutableResolver {
    private LauncherExecutableResolver() {
    }

    static Path resolveCurrent(Class<?> anchor) {
        String configured = System.getProperty("ecl.executable", "");
        if (configured.isBlank()) {
            configured = System.getenv("ECL_EXECUTABLE");
        }
        String runningCommand = ProcessHandle.current().info().command().orElse("");
        Path codeSource = null;
        try {
            codeSource = Path.of(anchor.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (URISyntaxException | RuntimeException ignored) {
            // Development launches do not have a packaged executable.
        }
        return resolveCandidate(configured, runningCommand,
                Path.of(System.getProperty("user.dir", ".")), codeSource);
    }

    static Path resolveCandidate(String configured, String runningCommand,
                                 Path workingDirectory, Path codeSource) {
        List<Path> candidates = new ArrayList<>();
        addExplicitCandidate(candidates, configured);
        if (isPackagedEclExecutable(runningCommand)) {
            candidates.add(Path.of(runningCommand));
        }
        if (workingDirectory != null) {
            candidates.add(workingDirectory.resolve("ECL.exe"));
        }
        if (codeSource != null) {
            if (Files.isDirectory(codeSource)) {
                candidates.add(codeSource.resolve("ECL.exe"));
            } else if (isPackagedEclExecutable(codeSource.toString())) {
                candidates.add(codeSource);
            }
            if (codeSource.getParent() != null) {
                candidates.add(codeSource.getParent().resolve("ECL.exe"));
            }
        }
        return candidates.stream()
                .filter(path -> path != null && Files.isRegularFile(path))
                .map(path -> path.toAbsolutePath().normalize())
                .findFirst().orElse(null);
    }

    private static void addExplicitCandidate(List<Path> candidates, String configured) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path candidate = Path.of(configured);
        String fileName = candidate.getFileName() == null
                ? "" : candidate.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            candidates.add(candidate);
        }
    }

    private static boolean isPackagedEclExecutable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(command);
            return path.getFileName() != null
                    && "ecl.exe".equalsIgnoreCase(path.getFileName().toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
