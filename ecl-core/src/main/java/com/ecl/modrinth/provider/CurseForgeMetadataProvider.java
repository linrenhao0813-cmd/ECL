package com.ecl.modrinth.provider;

import com.ecl.curseforge.CurseForgeApiClient;
import com.ecl.curseforge.CurseForgeFingerprint;
import com.ecl.curseforge.CurseForgeApiClient.ApiFile;
import com.ecl.curseforge.CurseForgeApiClient.ApiProject;
import com.ecl.curseforge.CurseForgeApiClient.ApiSearchResult;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Normalizes CurseForge projects and files into the existing provider model. */
public final class CurseForgeMetadataProvider implements ModMetadataProvider {
    private static final Set<String> LOADERS = Set.of("forge", "fabric", "quilt", "neoforge");
    private final CurseForgeApiClient api;

    public CurseForgeMetadataProvider(CurseForgeApiClient api) {
        this.api = java.util.Objects.requireNonNull(api, "api");
    }

    @Override
    public ContentSource source() {
        return ContentSource.CURSEFORGE;
    }

    @Override
    public CompletableFuture<ModSearchResult> search(ModSearchQuery query) {
        return async(() -> {
            ApiSearchResult result = api.search(query.keyword(), query.minecraftVersion(), "mod",
                    query.loader(), query.offset(), query.limit(), query.index());
            List<ModProject> projects = result.projects().stream()
                    .map(this::project).toList();
            return new ModSearchResult(projects, query.offset(), query.limit(),
                    result.totalCount());
        });
    }

    @Override
    public CompletableFuture<ModProject> getProject(String idOrSlug) {
        return async(() -> project(api.getProject(idOrSlug)));
    }

    @Override
    public CompletableFuture<ModVersion> getVersion(String versionId) {
        return async(() -> {
            String[] ids = versionIds(versionId);
            return version(api.getFile(ids[0], ids[1]));
        });
    }

    @Override
    public CompletableFuture<List<ModVersion>> getVersions(
            String projectId, String minecraftVersion, String loader) {
        return async(() -> api.getFiles(projectId, minecraftVersion, loader).stream()
                .map(this::versionUnchecked).toList());
    }

    @Override
    public CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
            Collection<String> hashes, String algorithm) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "CurseForge 不支持 SHA-1 反查，请使用 Murmur2 文件指纹识别"));
    }

    @Override
    public CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
            Collection<String> hashes, String algorithm, List<String> loaders,
            List<String> gameVersions) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "CurseForge 不支持 SHA-1 更新检查，请使用已保存的项目 ID"));
    }

    @Override
    public boolean supportsSha1HashLookup() {
        return false;
    }

    @Override
    public boolean canCheckUpdates(InstalledMod installedMod) {
        if (installedMod == null || installedMod.projectId() == null
                || installedMod.versionId() == null) {
            return false;
        }
        return installedMod.projectId().chars().allMatch(Character::isDigit)
                && installedMod.versionId().startsWith(installedMod.projectId() + ":");
    }

    @Override
    public CompletableFuture<Map<Path, ModVersion>> getVersionsByFiles(Collection<Path> files) {
        List<Path> paths = files == null ? List.of() : files.stream().distinct().toList();
        return async(() -> {
            Map<Path, Long> fingerprints = new LinkedHashMap<>();
            for (Path path : paths) {
                fingerprints.put(path, CurseForgeFingerprint.calculate(path));
            }
            Map<Long, ApiFile> matches = api.matchFingerprints(fingerprints.values());
            Map<Path, ModVersion> result = new LinkedHashMap<>();
            fingerprints.forEach((path, fingerprint) -> {
                ApiFile matched = matches.get(fingerprint);
                if (matched != null) {
                    result.put(path, versionUnchecked(matched));
                }
            });
            return Map.copyOf(result);
        });
    }

    private ModProject project(ApiProject value) {
        return new ModProject(
                value.id(), value.slug(), value.title(), value.author(), value.summary(), value.summary(),
                value.downloads(), 0, uri(value.iconUrl()), value.updatedAt(),
                Set.copyOf(value.categories()), List.of(), List.of(), "",
                "unknown", "unknown", uri(value.websiteUrl()), uri(value.sourceUrl()),
                uri(value.issuesUrl()));
    }

    private ModVersion versionUnchecked(ApiFile value) {
        try {
            return version(value);
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private ModVersion version(ApiFile value) throws IOException {
        List<String> loaders = value.gameVersions().stream()
                .map(text -> text.toLowerCase(Locale.ROOT))
                .filter(LOADERS::contains).distinct().toList();
        List<String> gameVersions = value.gameVersions().stream()
                .filter(text -> !LOADERS.contains(text.toLowerCase(Locale.ROOT))).toList();
        List<ModDependency> dependencies = value.dependencies().stream()
                .map(dependency -> new ModDependency("", dependency.projectId(), "",
                        switch (dependency.relationType()) {
                            case 1, 6 -> DependencyType.EMBEDDED;
                            case 2 -> DependencyType.OPTIONAL;
                            case 3 -> DependencyType.REQUIRED;
                            case 5 -> DependencyType.INCOMPATIBLE;
                            default -> DependencyType.UNKNOWN;
                        })).toList();
        URI downloadUri = uri(value.downloadUrl());
        if (downloadUri == null) {
            downloadUri = URI.create("curseforge://" + value.projectId() + "/" + value.id());
        }
        ModFile file = new ModFile(downloadUri, value.fileName(), value.hashes(),
                true, value.size(), "");
        return new ModVersion(value.projectId() + ":" + value.id(), value.projectId(),
                value.displayName(), value.displayName(), value.releaseType(), true, "listed",
                gameVersions, loaders, value.publishedAt(), "", List.of(file), dependencies);
    }

    private static String[] versionIds(String versionId) throws IOException {
        String[] parts = versionId == null ? new String[0] : versionId.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IOException("CurseForge 文件标识无效: " + versionId);
        }
        return parts;
    }

    private static URI uri(String value) {
        try {
            return value == null || value.isBlank() ? null : URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static <T> CompletableFuture<T> async(IoSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        });
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
