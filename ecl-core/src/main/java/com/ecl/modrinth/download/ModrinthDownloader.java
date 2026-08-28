package com.ecl.modrinth.download;

import com.ecl.download.ContentDownloader;
import com.ecl.modrinth.api.DefaultModrinthApiClient;
import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ContentDownloadResult;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/**
 * Synchronous content-library facade backed by the shared Modrinth API client.
 *
 * <p>The GUI still uses these small view-facing DTOs, but all HTTP, caching, retry and JSON
 * handling now lives in {@link DefaultModrinthApiClient}. This class only adapts API models to the
 * legacy content-library contract and performs verified file installation.</p>
 */
public class ModrinthDownloader implements ContentDownloader {
    private static final int MAX_DEPENDENCY_DEPTH = 32;
    private static final long MAX_CONTENT_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private final ModrinthApiClient apiClient;

    public interface DownloadListener {
        void onStatus(String message);

        void onProgress(long downloaded, long total);
    }

    public ModrinthDownloader() {
        this(new DefaultModrinthApiClient());
    }

    public ModrinthDownloader(ModrinthApiClient apiClient) {
        this.apiClient = java.util.Objects.requireNonNull(apiClient, "apiClient");
    }

    @Override
    public List<ContentProject> searchProjects(String query, String gameVersion, String projectType,
                                        String loader, int limit) throws IOException {
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isBlank()) {
            throw new IOException("请输入要搜索的内容名称");
        }
        return search(trimmedQuery, gameVersion, projectType, loader, ModSearchIndex.RELEVANCE, limit);
    }

    @Override
    public List<ContentProject> listOfficialProjects(String gameVersion, String projectType,
                                              String loader, int limit) throws IOException {
        return search("", gameVersion, projectType, loader, ModSearchIndex.DOWNLOADS, limit);
    }

    public List<ContentProject> searchMods(String query, String gameVersion, String loader, int limit)
            throws IOException {
        return searchProjects(query, gameVersion, "mod", loader, limit);
    }

    private List<ContentProject> search(String query, String gameVersion, String projectType, String loader,
                                 ModSearchIndex index, int limit) throws IOException {
        ModrinthDownloadSupport.requireText(gameVersion, "请先选择游戏版本");
        ModrinthDownloadSupport.requireText(projectType, "内容类型无效");
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        ModSearchQuery request = new ModSearchQuery(query, gameVersion, loader, projectType,
                Set.of(), index, 0, boundedLimit);
        return ModrinthDownloadSupport.await(apiClient.searchMods(request)).hits().stream()
                .map(project -> ModrinthContentMapper.toProject(project, projectType))
                .toList();
    }

    @Override
    public List<ContentVersion> listProjectVersions(ContentProject project, String gameVersion, String loader)
            throws IOException {
        ModrinthDownloadSupport.requireProject(project);
        List<ModVersion> versions = ModrinthDownloadSupport.await(apiClient.getProjectVersions(
                project.getProjectId(), ModrinthDownloadSupport.requireText(
                        gameVersion, "请先选择游戏版本"), loader));
        return versions.stream().map(ModrinthContentMapper::toProjectVersion).toList();
    }

    public ContentDownloadResult downloadLatest(ContentProject project, String gameVersion, String loader,
                                         File targetDir, boolean includeRequiredDependencies,
                                         DownloadListener listener, String... allowedExtensions)
            throws IOException {
        ModrinthDownloadSupport.requireProject(project);
        List<ContentVersion> versions = listProjectVersions(project, gameVersion, loader);
        if (versions.isEmpty()) {
            throw new IOException("没有找到兼容 " + gameVersion + " / " + loader + " 的可下载文件");
        }
        return downloadVersion(project, versions.get(0), gameVersion, loader, targetDir,
                includeRequiredDependencies, listener, allowedExtensions);
    }

    public ContentDownloadResult downloadLatest(ContentProject project, String gameVersion, String loader,
                                         File targetDir, DownloadListener listener) throws IOException {
        return downloadLatest(project, gameVersion, loader, targetDir, true, listener, ".jar");
    }

    @Override
    public ContentDownloadResult downloadVersion(ContentProject project, ContentVersion selectedVersion,
                                          String gameVersion, String loader, File targetDir,
                                          boolean includeRequiredDependencies,
                                          DownloadListener listener, String... allowedExtensions)
            throws IOException {
        ModrinthDownloadSupport.requireProject(project);
        if (selectedVersion == null) {
            throw new IOException("请选择项目和具体版本");
        }
        File destination = ModrinthDownloadSupport.requireTargetDirectory(targetDir);
        ModVersion version = ModrinthDownloadSupport.await(apiClient.getVersion(selectedVersion.versionId()));
        if (!project.getProjectId().equals(version.projectId())) {
            throw new IOException("所选版本不属于当前 Modrinth 项目");
        }

        List<File> files = new ArrayList<>();
        downloadVersion(version, gameVersion, loader, destination, listener,
                new HashSet<>(), new ArrayDeque<>(), files, new HashMap<>(), true,
                includeRequiredDependencies, allowedExtensions);
        if (files.isEmpty()) {
            throw new IOException("这个版本没有可下载文件");
        }
        return new ContentDownloadResult(files.get(0), files);
    }

    private File downloadVersion(ModVersion version, String gameVersion, String loader, File targetDir,
                                 DownloadListener listener, Set<String> visitedVersions,
                                 Deque<String> dependencyPath, List<File> files,
                                 Map<Path, String> plannedTargets, boolean primary,
                                 boolean includeRequiredDependencies, String... allowedExtensions)
            throws IOException {
        ModrinthDownloadSupport.ensureCompatibleVersion(version, gameVersion, loader);
        if (dependencyPath.size() >= MAX_DEPENDENCY_DEPTH) {
            throw new IOException("Modrinth dependency chain exceeds " + MAX_DEPENDENCY_DEPTH
                    + " levels");
        }
        String versionId = version.id();
        boolean tracked = versionId != null && !versionId.isBlank();
        if (tracked) {
            ModrinthDependencyGuard.ensureNoDependencyCycle(dependencyPath, versionId);
            if (!visitedVersions.add(versionId)) {
                return null;
            }
            dependencyPath.addLast(versionId);
        }

        try {
            ModFile file = ModrinthFileSelector.selectPrimaryFile(version.files(), allowedExtensions);
            String filename = ModrinthDownloadSupport.sanitizeFilename(file.fileName());
            if (file.url() == null || filename == null || filename.isBlank()) {
                throw new IOException("Modrinth 文件信息不完整，无法创建本地文件");
            }
            if (!HashVerifier.hasUsableExpectedHash(file.hashes())) {
                throw new IOException("Modrinth 文件缺少 SHA-512 或 SHA-1: " + filename);
            }
            if (file.size() <= 0 || file.size() > MAX_CONTENT_FILE_BYTES) {
                throw new IOException("Modrinth 文件大小声明无效: " + filename);
            }
            java.net.URI downloadUri = NetworkUriPolicy.requireSecureDownload(
                    file.url(), "Modrinth 下载地址");

            File target = new File(targetDir, filename);
            Path normalizedTarget = target.toPath().toAbsolutePath().normalize();
            String targetHash = file.sha512() == null || file.sha512().isBlank()
                    ? file.sha1() : file.sha512();
            String priorHash = plannedTargets.putIfAbsent(normalizedTarget, targetHash.toLowerCase());
            if (priorHash != null && !priorHash.equalsIgnoreCase(targetHash)) {
                throw new IOException("Modrinth dependency files collide at target: " + filename);
            }
            if (ModrinthFileSelector.existingFileSatisfies(target, file)) {
                ModrinthDownloadSupport.notifyStatus(
                        listener, (primary ? "文件已存在，跳过下载: " : "依赖已存在，跳过下载: ")
                        + filename);
            } else {
                ModrinthDownloadSupport.notifyStatus(
                        listener, (primary ? "正在下载: " : "正在下载依赖: ") + filename);
                Path temporary = target.toPath().toAbsolutePath().normalize()
                        .resolveSibling(target.getName() + ".ecl-download-" + UUID.randomUUID() + ".tmp");
                try {
                HttpUtil.downloadFileWithProgress(downloadUri.toString(), temporary.toFile(),
                        new HttpUtil.ProgressCallback() {
                            @Override
                            public void onStart(long total) {
                                ModrinthDownloadSupport.notifyProgress(listener, 0, total);
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                ModrinthDownloadSupport.notifyProgress(listener, downloaded, total);
                            }

                            @Override
                            public void onComplete(File downloaded) {
                                ModrinthDownloadSupport.notifyProgress(listener, 1, 1);
                            }
                        }, null, file.size());
                if (Files.size(temporary) != file.size()) {
                    throw new IOException("文件大小校验失败: " + filename);
                }
                new HashVerifier().verify(temporary, file.hashes());
                try {
                    Files.move(temporary, target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            files.add(target);
            if (includeRequiredDependencies) {
                downloadRequiredDependencies(version, gameVersion, loader, targetDir, listener,
                        visitedVersions, dependencyPath, files, plannedTargets);
            }
            return target;
        } finally {
            if (tracked) {
                dependencyPath.removeLast();
            }
        }
    }

    private void downloadRequiredDependencies(ModVersion version, String gameVersion, String loader,
                                              File targetDir, DownloadListener listener,
                                              Set<String> visitedVersions, Deque<String> dependencyPath,
                                              List<File> files, Map<Path, String> plannedTargets) throws IOException {
        for (ModDependency dependency : version.dependencies()) {
            if (dependency.type() != DependencyType.REQUIRED) {
                continue;
            }
            ModVersion dependencyVersion = resolveDependency(dependency, gameVersion, loader);
            if (dependencyVersion != null) {
                downloadVersion(dependencyVersion, gameVersion, loader, targetDir, listener,
                        visitedVersions, dependencyPath, files, plannedTargets, false, true, ".jar");
            }
        }
    }

    private ModVersion resolveDependency(ModDependency dependency, String gameVersion, String loader)
            throws IOException {
        if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            return ModrinthDownloadSupport.await(apiClient.getVersion(dependency.versionId()));
        }
        if (dependency.projectId() == null || dependency.projectId().isBlank()) {
            return null;
        }
        List<ModVersion> versions = ModrinthDownloadSupport.await(apiClient.getProjectVersions(
                dependency.projectId(), gameVersion, loader));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private boolean isAllowedFilename(String filename, String... allowedExtensions) {
        return ModrinthFileSelector.isAllowedFilename(filename, allowedExtensions);
    }

    static void ensureNoDependencyCycle(Deque<String> dependencyPath, String versionId)
            throws IOException {
        ModrinthDependencyGuard.ensureNoDependencyCycle(dependencyPath, versionId);
    }

    static boolean existingFileSatisfies(File target, String sha1) {
        return ModrinthFileSelector.existingFileSatisfies(target, sha1);
    }
}
