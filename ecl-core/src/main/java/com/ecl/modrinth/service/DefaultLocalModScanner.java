package com.ecl.modrinth.service;

import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.repository.InstalledModRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class DefaultLocalModScanner implements LocalModScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLocalModScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long MAX_METADATA_BYTES = 1024 * 1024;
    private static final List<String> METADATA_FILES = List.of(
            "fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml",
            "META-INF/neoforge.mods.toml", "mcmod.info");

    private final ModrinthApiClient apiClient;
    private final InstalledModRepository repository;
    private final HashVerifier hashVerifier;
    private final ModVersionSelector versionSelector;
    private final InstanceOperationLock operationLock;
    private final Executor executor;
    private final Predicate<UUID> instanceRunning;

    public DefaultLocalModScanner(
            ModrinthApiClient apiClient,
            InstalledModRepository repository,
            HashVerifier hashVerifier,
            ModVersionSelector versionSelector,
            InstanceOperationLock operationLock,
            Executor executor,
            Predicate<UUID> instanceRunning
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.hashVerifier = Objects.requireNonNull(hashVerifier, "hashVerifier");
        this.versionSelector = Objects.requireNonNull(versionSelector, "versionSelector");
        this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
    }

    @Override
    public CompletableFuture<LocalModScanResult> scan(ModInstanceContext instance) {
        return CompletableFuture.supplyAsync(() -> scanBlocking(instance), executor);
    }

    private LocalModScanResult scanBlocking(ModInstanceContext instance) {
        if (instanceRunning.test(instance.instanceId())) {
            throw new com.ecl.modrinth.api.ModInstallationException(
                    "实例正在运行，不能修复模组索引");
        }
        try (AutoCloseable ignored = operationLock.acquire(instance.instanceId())) {
            Files.createDirectories(instance.modsDirectory());
            Files.createDirectories(instance.gameDirectory().resolve("disabled-mods"));
            List<InstalledMod> previous = repository.findAll(instance);
            Map<String, InstalledMod> previousByPath = new HashMap<>();
            previous.forEach(mod -> previousByPath.put(normalizeRelative(mod.relativePath()), mod));
            Map<String, ScanCacheEntry> cache = readCache(instance);
            List<ScannedFile> scanned = scanFiles(instance, cache);

            List<String> hashes = scanned.stream()
                    .filter(file -> !file.damaged)
                    .map(file -> file.hashes.sha1())
                    .distinct()
                    .toList();
            Map<String, ModVersion> recognized = hashes.isEmpty()
                    ? Map.of()
                    : apiClient.getVersionsFromHashes(hashes, "sha1").join();

            List<InstalledMod> records = new ArrayList<>();
            List<LocalModScanItem> items = new ArrayList<>();
            Map<String, Integer> projectCounts = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            for (ScannedFile file : scanned) {
                Path relative = instance.gameDirectory().relativize(file.path);
                InstalledMod old = previousByPath.get(normalizeRelative(relative));
                if (file.damaged) {
                    if (old != null) {
                        records.add(old);
                    }
                    items.add(new LocalModScanItem(file.path, old, false, true, file.message));
                    warnings.add(file.path.getFileName() + ": " + file.message);
                    continue;
                }
                ModVersion version = recognized.get(file.hashes.sha1());
                boolean enabled = file.path.getParent().equals(instance.modsDirectory());
                InstalledMod record;
                if (version != null) {
                    ModFile matchedFile = version.files().stream()
                            .filter(candidate -> file.hashes.sha1().equalsIgnoreCase(candidate.sha1()))
                            .findFirst()
                            .orElseGet(() -> versionSelector.selectInstallFile(version).orElse(null));
                    record = recognizedRecord(instance, version, file, relative, enabled, old, matchedFile);
                    projectCounts.merge(record.projectId(), 1, Integer::sum);
                    items.add(new LocalModScanItem(file.path, record, true, false, "Modrinth 已识别"));
                } else {
                    record = unknownRecord(instance, file, relative, enabled, old);
                    items.add(new LocalModScanItem(file.path, record, false, false, "本地或未知来源"));
                }
                records.add(record);
            }
            Set<String> scannedPaths = scanned.stream()
                    .map(file -> normalizeRelative(instance.gameDirectory().relativize(file.path)))
                    .collect(java.util.stream.Collectors.toSet());
            for (InstalledMod old : previous) {
                if (scannedPaths.contains(normalizeRelative(old.relativePath()))) {
                    continue;
                }
                records.add(old);
                Path missingFile = instance.gameDirectory().resolve(old.relativePath()).normalize();
                items.add(new LocalModScanItem(missingFile, old, false, false, "安装记录对应的文件缺失"));
                warnings.add(old.displayName() + ": 安装记录对应的文件缺失");
            }
            repository.saveAll(instance, records);
            writeCache(instance, scanned);
            List<String> duplicates = projectCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!duplicates.isEmpty()) {
                warnings.add("检测到同一项目的多个版本: " + String.join("、", duplicates));
            }
            return new LocalModScanResult(records, items, duplicates, warnings);
        } catch (Exception e) {
            throw new com.ecl.modrinth.api.ModInstallationException("扫描本地模组失败", e);
        }
    }

    private List<ScannedFile> scanFiles(ModInstanceContext instance, Map<String, ScanCacheEntry> cache)
            throws IOException {
        List<ScannedFile> result = new ArrayList<>();
        for (Path directory : List.of(
                instance.modsDirectory(),
                instance.gameDirectory().resolve("disabled-mods"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted().toList()) {
                    String key = normalizeRelative(instance.gameDirectory().relativize(file));
                    long size = Files.size(file);
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    ScanCacheEntry cached = cache.get(key);
                    HashVerifier.HashResult hashes;
                    if (cached != null && cached.size() == size && cached.modifiedAt() == modified
                            && !cached.sha1().isBlank() && !cached.sha512().isBlank()) {
                        hashes = new HashVerifier.HashResult(cached.sha1(), cached.sha512());
                    } else {
                        hashes = hashVerifier.calculate(file);
                    }
                    String damage = validateJar(file);
                    result.add(new ScannedFile(file.toAbsolutePath().normalize(), size, modified,
                            hashes, damage != null, damage == null ? "" : damage));
                }
            }
        }
        return result;
    }

    private static String validateJar(Path file) {
        try (JarFile jar = new JarFile(file.toFile())) {
            for (String metadataName : METADATA_FILES) {
                JarEntry entry = jar.getJarEntry(metadataName);
                if (entry == null) {
                    continue;
                }
                if (entry.getSize() > MAX_METADATA_BYTES) {
                    return "模组元数据超过安全读取上限";
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_METADATA_BYTES) {
                            return "模组元数据解压后超过安全读取上限";
                        }
                    }
                }
                break;
            }
            return null;
        } catch (IOException e) {
            return "JAR 损坏或无法读取: " + e.getMessage();
        }
    }

    private static InstalledMod recognizedRecord(
            ModInstanceContext instance,
            ModVersion version,
            ScannedFile file,
            Path relative,
            boolean enabled,
            InstalledMod old,
            ModFile matchedFile
    ) {
        Instant now = Instant.now();
        return new InstalledMod(
                instance.instanceId(), version.projectId(), version.id(), "",
                firstNonBlank(version.name(), version.projectId()), version.versionNumber(),
                file.path.getFileName().toString(), relative,
                file.hashes.sha1(), file.hashes.sha512(), file.size,
                instance.minecraftVersion(), instance.loaderName(), version.versionType(), enabled,
                old != null && old.dependency(), old == null ? "" : old.requiredByProjectId(),
                old == null || old.installedAt() == null ? now : old.installedAt(), now);
    }

    private static InstalledMod unknownRecord(
            ModInstanceContext instance, ScannedFile file, Path relative, boolean enabled, InstalledMod old) {
        Instant now = Instant.now();
        return new InstalledMod(
                instance.instanceId(),
                old == null || old.projectId().isBlank() ? "local:" + file.hashes.sha1() : old.projectId(),
                old == null ? "" : old.versionId(),
                old == null ? "" : old.projectSlug(),
                old == null || old.displayName().isBlank()
                        ? file.path.getFileName().toString() : old.displayName(),
                old == null ? "" : old.versionNumber(),
                file.path.getFileName().toString(),
                relative,
                file.hashes.sha1(), file.hashes.sha512(), file.size,
                instance.minecraftVersion(), instance.loaderName(),
                old == null ? "local" : old.versionType(), enabled,
                old != null && old.dependency(), old == null ? "" : old.requiredByProjectId(),
                old == null || old.installedAt() == null ? now : old.installedAt(), now);
    }

    private Map<String, ScanCacheEntry> readCache(ModInstanceContext instance) {
        Path path = cachePath(instance);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            ScanCache dto = MAPPER.readValue(path.toFile(), ScanCache.class);
            Map<String, ScanCacheEntry> result = new HashMap<>();
            dto.entries().forEach(entry -> result.put(entry.relativePath(), entry));
            return result;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring invalid local mod scan cache {}", path, e);
            return Map.of();
        }
    }

    private void writeCache(ModInstanceContext instance, Collection<ScannedFile> files) throws IOException {
        Path target = cachePath(instance);
        Files.createDirectories(target.getParent());
        List<ScanCacheEntry> entries = files.stream().map(file -> new ScanCacheEntry(
                normalizeRelative(instance.gameDirectory().relativize(file.path)),
                file.size, file.modifiedAt, file.hashes.sha1(), file.hashes.sha512())).toList();
        Path temporary = Files.createTempFile(target.getParent(), "mod-scan-", ".json.tmp");
        try {
            Files.writeString(temporary,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new ScanCache(entries)),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path cachePath(ModInstanceContext instance) {
        return instance.gameDirectory().resolve("launcher-mod-scan.json");
    }

    private static String normalizeRelative(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record ScannedFile(
            Path path,
            long size,
            long modifiedAt,
            HashVerifier.HashResult hashes,
            boolean damaged,
            String message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScanCache(List<ScanCacheEntry> entries) {
        private ScanCache {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScanCacheEntry(
            String relativePath,
            long size,
            long modifiedAt,
            String sha1,
            String sha512
    ) {
    }
}
