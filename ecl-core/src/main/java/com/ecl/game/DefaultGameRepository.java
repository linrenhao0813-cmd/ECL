package com.ecl.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Filesystem-backed version metadata and instance path policy. */
public final class DefaultGameRepository implements GameRepository {
    private final Path versionsDirectory;
    private final Path sharedGameDirectory;
    private final VersionRepository versions;

    public DefaultGameRepository(Path versionsDirectory, Path sharedGameDirectory) {
        this.versionsDirectory = Objects.requireNonNull(versionsDirectory, "versionsDirectory")
                .toAbsolutePath().normalize();
        this.sharedGameDirectory = Objects.requireNonNull(sharedGameDirectory, "sharedGameDirectory")
                .toAbsolutePath().normalize();
        this.versions = new VersionRepository(this.versionsDirectory.toFile());
    }

    @Override
    public VersionMetadata resolve(String versionId) throws IOException {
        return versions.resolve(versionId);
    }

    @Override
    public List<String> installedVersions() {
        if (!Files.isDirectory(versionsDirectory)) return List.of();
        try (Stream<Path> entries = Files.list(versionsDirectory)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve(path.getFileName() + ".json")))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    @Override
    public Path instanceDirectory(String versionId, InstanceIsolation isolation, Path customDirectory) {
        String safeVersion = requireSafeVersionId(versionId);
        InstanceIsolation effective = isolation == null ? InstanceIsolation.VERSION_ISOLATED : isolation;
        return switch (effective) {
            case GLOBAL_SHARED -> sharedGameDirectory;
            case VERSION_ISOLATED -> sharedGameDirectory.resolve("versions").resolve(safeVersion).normalize();
            case CUSTOM -> {
                if (customDirectory == null) {
                    throw new IllegalArgumentException("Custom instance directory is required");
                }
                yield customDirectory.toAbsolutePath().normalize();
            }
        };
    }

    private static String requireSafeVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()
                || versionId.contains("/") || versionId.contains("\\") || versionId.contains("..")) {
            throw new IllegalArgumentException("Invalid version id");
        }
        return versionId.trim();
    }
}
