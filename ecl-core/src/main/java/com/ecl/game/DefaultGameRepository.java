package com.ecl.game;

import com.ecl.util.FileUtil;
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
    private final DefaultIsolationType defaultIsolationType;
    private final InstanceGameSettingsStore instanceSettings;

    public DefaultGameRepository(Path versionsDirectory, Path sharedGameDirectory) {
        this(versionsDirectory, sharedGameDirectory, DefaultIsolationType.MODDED);
    }

    public DefaultGameRepository(Path versionsDirectory, Path sharedGameDirectory,
                                 DefaultIsolationType defaultIsolationType) {
        this.versionsDirectory = Objects.requireNonNull(versionsDirectory, "versionsDirectory")
                .toAbsolutePath().normalize();
        this.sharedGameDirectory = Objects.requireNonNull(sharedGameDirectory, "sharedGameDirectory")
                .toAbsolutePath().normalize();
        this.versions = new VersionRepository(this.versionsDirectory.toFile());
        this.defaultIsolationType = defaultIsolationType == null
                ? DefaultIsolationType.MODDED : defaultIsolationType;
        this.instanceSettings = new InstanceGameSettingsStore();
    }

    @Override
    public Path instanceRoot(String versionId) {
        return sharedGameDirectory.resolve("versions").resolve(requireSafeVersionId(versionId)).normalize();
    }

    @Override
    public Path runDirectory(String versionId) throws IOException {
        Path root = instanceRoot(versionId);
        VersionMetadata metadata = resolve(versionId);
        if (metadata.isModpack()) {
            return root;
        }
        boolean settingsExist = Files.isRegularFile(instanceSettings.settingsFile(root));
        InstanceGameSettings settings = instanceSettings.load(root);
        if (settings.overridesRunningDirectory()) {
            return settings.hasCustomDirectory()
                    ? Path.of(settings.runningDirectory()).toAbsolutePath().normalize() : root;
        }
        if (!settingsExist && hasLegacyIsolatedData(root)) {
            return root;
        }
        return shouldIsolate(metadata) ? root : sharedGameDirectory;
    }

    public void setIsolated(String versionId) throws IOException {
        instanceSettings.save(instanceRoot(versionId), InstanceGameSettings.isolated());
    }

    public void setCustomRunDirectory(String versionId, Path directory) throws IOException {
        instanceSettings.save(instanceRoot(versionId), InstanceGameSettings.custom(directory));
    }

    public void inheritRunDirectoryPolicy(String versionId) throws IOException {
        instanceSettings.save(instanceRoot(versionId), InstanceGameSettings.inherited());
    }

    public void applyDefaultIsolationSettingForNewInstance(String versionId) throws IOException {
        Path root = instanceRoot(versionId);
        if (Files.isRegularFile(instanceSettings.settingsFile(root))) {
            return;
        }
        VersionMetadata metadata = resolve(versionId);
        if (metadata.isModpack() || shouldIsolate(metadata)) {
            instanceSettings.save(root, InstanceGameSettings.isolated());
        }
    }

    private boolean shouldIsolate(VersionMetadata metadata) {
        return switch (defaultIsolationType) {
            case ALWAYS -> true;
            case MODDED -> LibraryAnalyzer.isModded(metadata);
            case NEVER -> false;
        };
    }

    private boolean hasLegacyIsolatedData(Path instanceRoot) {
        for (String name : List.of("mods", "saves", "config", "logs", "resourcepacks",
                "shaderpacks", "screenshots", "options.txt", "servers.dat")) {
            if (Files.exists(instanceRoot.resolve(name))) {
                return true;
            }
        }
        return false;
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
            case VERSION_ISOLATED -> instanceRoot(safeVersion);
            case CUSTOM -> {
                if (customDirectory == null) {
                    throw new IllegalArgumentException("Custom instance directory is required");
                }
                yield customDirectory.toAbsolutePath().normalize();
            }
        };
    }

    private static String requireSafeVersionId(String versionId) {
        try {
            FileUtil.requireSafeVersionId(versionId);
            return versionId.trim();
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid version id", error);
        }
    }
}
