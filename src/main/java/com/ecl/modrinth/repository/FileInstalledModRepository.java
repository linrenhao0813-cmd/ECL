package com.ecl.modrinth.repository;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FileInstalledModRepository implements InstalledModRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileInstalledModRepository.class);
    private static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ConcurrentHashMap<UUID, Object> instanceMonitors = new ConcurrentHashMap<>();

    @Override
    public List<InstalledMod> findAll(ModInstanceContext instance) throws IOException {
        synchronized (monitor(instance)) {
            Path index = indexPath(instance);
            if (!Files.isRegularFile(index)) {
                return List.of();
            }
            try {
                IndexDto dto = MAPPER.readValue(index.toFile(), IndexDto.class);
                return dto.mods().stream().map(FileInstalledModRepository::fromDto).toList();
            } catch (RuntimeException e) {
                throw new IOException("Invalid installed mod index: " + index, e);
            }
        }
    }

    @Override
    public Optional<InstalledMod> findByProjectId(ModInstanceContext instance, String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return findAll(instance).stream().filter(mod -> projectId.equals(mod.projectId())).findFirst();
    }

    @Override
    public void saveAll(ModInstanceContext instance, Collection<InstalledMod> mods) throws IOException {
        synchronized (monitor(instance)) {
            Path target = indexPath(instance);
            Files.createDirectories(target.getParent());
            Path staged = createSnapshot(instance, mods, target.getParent());
            try {
                move(staged, target);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    @Override
    public Path createSnapshot(ModInstanceContext instance, Collection<InstalledMod> mods, Path stagingDirectory)
            throws IOException {
        Path stagingRoot = stagingDirectory.toAbsolutePath().normalize();
        Files.createDirectories(stagingRoot);
        Path staged = Files.createTempFile(stagingRoot, "launcher-mods-", ".json.tmp");
        List<InstalledMod> ordered = new ArrayList<>(mods == null ? List.of() : mods);
        ordered.sort(Comparator.comparing(InstalledMod::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(InstalledMod::projectId));
        IndexDto dto = new IndexDto(SCHEMA_VERSION, instance.instanceId().toString(),
                Instant.now().toString(), ordered.stream().map(FileInstalledModRepository::toDto).toList());
        Files.writeString(staged, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(dto),
                StandardCharsets.UTF_8);
        return staged;
    }

    @Override
    public Path indexPath(ModInstanceContext instance) {
        return instance.gameDirectory().resolve("launcher-mods.json").toAbsolutePath().normalize();
    }

    private Object monitor(ModInstanceContext instance) {
        return instanceMonitors.computeIfAbsent(instance.instanceId(), ignored -> new Object());
    }

    private static InstalledModDto toDto(InstalledMod mod) {
        return new InstalledModDto(
                mod.instanceId().toString(), mod.projectId(), mod.versionId(), mod.projectSlug(),
                mod.displayName(), mod.versionNumber(), mod.fileName(), mod.relativePath().toString(),
                mod.sha1(), mod.sha512(), mod.fileSize(), mod.minecraftVersion(), mod.loader(),
                mod.versionType(), mod.enabled(), mod.dependency(), mod.requiredByProjectId(),
                string(mod.installedAt()), string(mod.updatedAt()));
    }

    private static InstalledMod fromDto(InstalledModDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Installed mod entry is null");
        }
        UUID instanceId = parseUuid(dto.instanceId(), "instanceId");
        Path relativePath = parseRelativePath(dto.relativePath());
        return new InstalledMod(
                instanceId,
                text(dto.projectId()), text(dto.versionId()), text(dto.projectSlug()),
                text(dto.displayName()), text(dto.versionNumber()), text(dto.fileName()),
                relativePath, text(dto.sha1()), text(dto.sha512()), dto.fileSize(),
                text(dto.minecraftVersion()), text(dto.loader()), text(dto.versionType()),
                dto.enabled(), dto.dependency(), text(dto.requiredByProjectId()),
                instant(dto.installedAt()), instant(dto.updatedAt()));
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Installed mod " + field + " is missing");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Installed mod " + field + " is invalid: " + value, e);
        }
    }

    private static Path parseRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Installed mod relativePath is missing");
        }
        final Path path;
        try {
            path = Path.of(value).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Installed mod relativePath is invalid: " + value, e);
        }
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("Installed mod relativePath escapes the instance: " + value);
        }
        return path;
    }

    private static String string(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexDto(int schemaVersion, String instanceId, String updatedAt, List<InstalledModDto> mods) {
        public IndexDto {
            mods = mods == null ? List.of() : List.copyOf(mods);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstalledModDto(
            String instanceId,
            String projectId,
            String versionId,
            String projectSlug,
            String displayName,
            String versionNumber,
            String fileName,
            String relativePath,
            String sha1,
            String sha512,
            long fileSize,
            String minecraftVersion,
            String loader,
            String versionType,
            boolean enabled,
            boolean dependency,
            String requiredByProjectId,
            String installedAt,
            String updatedAt
    ) {
    }
}
