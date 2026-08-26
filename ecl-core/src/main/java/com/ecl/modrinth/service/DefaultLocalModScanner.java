package com.ecl.modrinth.service;

import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.provider.ModrinthMetadataProvider;
import com.ecl.modrinth.repository.InstalledModRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class DefaultLocalModScanner implements LocalModScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLocalModScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ModMetadataProvider metadataProvider;
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
        this(new ModrinthMetadataProvider(apiClient, false), repository, hashVerifier,
                versionSelector, operationLock, executor, instanceRunning);
    }

    public DefaultLocalModScanner(
            ModMetadataProvider metadataProvider,
            InstalledModRepository repository,
            HashVerifier hashVerifier,
            ModVersionSelector versionSelector,
            InstanceOperationLock operationLock,
            Executor executor,
            Predicate<UUID> instanceRunning
    ) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
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

            List<Path> scannableFiles = scanned.stream()
                    .filter(file -> !file.damaged)
                    .map(file -> file.path)
                    .distinct()
                    .toList();
            Map<Path, ModVersion> recognized;
            try {
                recognized = scannableFiles.isEmpty() ? Map.of()
                        : metadataProvider.getVersionsByFiles(scannableFiles).join();
            } catch (RuntimeException lookupFailure) {
                LOGGER.warn("Online mod hash lookup failed; continuing with local metadata",
                        lookupFailure);
                recognized = Map.of();
            }

            List<InstalledMod> records = new ArrayList<>();
            List<LocalModScanItem> items = new ArrayList<>();
            Map<String, Integer> projectCounts = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            // 预建每个版本的 sha1 → ModFile 索引，避免对每个本地文件做线性 findFirst。
            Map<String, Map<String, ModFile>> versionFileIndex = new HashMap<>();
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
                ModVersion version = recognized.get(file.path);
                boolean enabled = file.path.getParent().equals(instance.modsDirectory());
                InstalledMod record;
                if (version != null) {
                    ModFile matchedFile = matchFileBySha1(version, file.hashes.sha1(),
                            versionFileIndex, versionSelector);
                    record = recognizedRecord(instance, version, file, relative, enabled, old, matchedFile);
                    projectCounts.merge(record.projectId(), 1, Integer::sum);
                    items.add(new LocalModScanItem(file.path, record, true, false,
                            metadataProvider.source().displayName() + " 已识别"));
                } else {
                    record = unknownRecord(instance, file, relative, enabled, old);
                    String message = file.metadata.modded()
                            ? "已从 JAR 元数据识别（" + loaderLabel(file.metadata.loader()) + "）"
                            : "本地或未知来源";
                    items.add(new LocalModScanItem(file.path, record, false, false, message));
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
            // Broad catch is required: operationLock.acquire() AutoCloseable.close() declares Exception.
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
                    boolean cacheMatches = cached != null && cached.size() == size
                            && cached.modifiedAt() == modified;
                    if (cacheMatches
                            && !cached.sha1().isBlank() && !cached.sha512().isBlank()) {
                        hashes = new HashVerifier.HashResult(cached.sha1(), cached.sha512());
                    } else {
                        hashes = hashVerifier.calculate(file);
                    }
                    LocalModMeta cachedMetadata = cacheMatches ? metadataFromCache(cached) : null;
                    LocalModMetadataReader.Inspection inspection = cachedMetadata == null
                            ? LocalModMetadataReader.inspectJar(file)
                            : new LocalModMetadataReader.Inspection(null, cachedMetadata);
                    result.add(new ScannedFile(file.toAbsolutePath().normalize(), size, modified,
                            hashes, inspection.damage() != null,
                            inspection.damage() == null ? "" : inspection.damage(),
                            inspection.metadata()));
                }
            }
        }
        return result;
    }

    private static LocalModMeta metadataFromCache(ScanCacheEntry cached) {
        ModLoader loader = ModLoader.fromApiName(cached.loader());
        if (!loader.supportsMods()) {
            return null;
        }
        return new LocalModMeta(cached.modId(), cached.modName(), cached.modVersion(), loader, true);
    }

    private static String loaderLabel(ModLoader loader) {
        return switch (loader) {
            case FABRIC -> "Fabric";
            case QUILT -> "Quilt";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
            case NONE -> "未知";
        };
    }

    /**
     * Match a scanned file against a version's files by SHA-1 using a per-version index.
     * Falls back to the version selector when the hash is missing or unmatched.
     */
    private static ModFile matchFileBySha1(
            ModVersion version,
            String sha1,
            Map<String, Map<String, ModFile>> versionFileIndex,
            ModVersionSelector versionSelector
    ) {
        Map<String, ModFile> bySha1 = versionFileIndex.computeIfAbsent(version.id(), ignored -> {
            Map<String, ModFile> index = new HashMap<>();
            for (ModFile candidate : version.files()) {
                if (candidate.sha1() != null && !candidate.sha1().isBlank()) {
                    index.putIfAbsent(candidate.sha1().toLowerCase(Locale.ROOT), candidate);
                }
            }
            return index;
        });
        if (sha1 != null && !sha1.isBlank()) {
            ModFile matched = bySha1.get(sha1.toLowerCase(Locale.ROOT));
            if (matched != null) {
                return matched;
            }
        }
        return versionSelector.selectInstallFile(version).orElse(null);
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
                        ? firstNonBlank(file.metadata.name(), file.path.getFileName().toString())
                        : old.displayName(),
                old == null || old.versionNumber().isBlank()
                        ? file.metadata.version() : old.versionNumber(),
                file.path.getFileName().toString(),
                relative,
                file.hashes.sha1(), file.hashes.sha512(), file.size,
                instance.minecraftVersion(), file.metadata.loader().supportsMods()
                        ? file.metadata.loader().apiName() : instance.loaderName(),
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
                file.size, file.modifiedAt, file.hashes.sha1(), file.hashes.sha512(),
                file.metadata.id(), file.metadata.name(), file.metadata.version(),
                file.metadata.loader().apiName())).toList();
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
            String message,
            LocalModMeta metadata
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
            String sha512,
            String modId,
            String modName,
            String modVersion,
            String loader
    ) {
        private ScanCacheEntry {
            modId = modId == null ? "" : modId;
            modName = modName == null ? "" : modName;
            modVersion = modVersion == null ? "" : modVersion;
            loader = loader == null ? "" : loader;
        }
    }
}
