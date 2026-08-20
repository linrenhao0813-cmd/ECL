package com.ecl.download;

import com.ecl.modrinth.api.DefaultModrinthApiClient;
import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;


/**
 * Synchronous content-library facade backed by the shared Modrinth API client.
 *
 * <p>The GUI still uses these small view-facing DTOs, but all HTTP, caching, retry and JSON
 * handling now lives in {@link DefaultModrinthApiClient}. This class only adapts API models to the
 * legacy content-library contract and performs verified file installation.</p>
 */
public class ModrinthDownloader implements ContentDownloader {
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

    public static class Project {
        private final String projectId;
        private final String slug;
        private final String title;
        private final String author;
        private final String description;
        private final String iconUrl;
        private final long downloads;
        private final long follows;
        private final String projectType;

        public Project(String projectId, String slug, String title, String author, String description,
                       long downloads, long follows) {
            this(projectId, slug, title, author, description, null, downloads, follows);
        }

        public Project(String projectId, String slug, String title, String author, String description,
                       String iconUrl, long downloads, long follows) {
            this(projectId, slug, title, author, description, iconUrl, downloads, follows, "");
        }

        public Project(String projectId, String slug, String title, String author, String description,
                       String iconUrl, long downloads, long follows, String projectType) {
            this.projectId = projectId;
            this.slug = slug;
            this.title = title;
            this.author = author;
            this.description = description;
            this.iconUrl = iconUrl;
            this.downloads = downloads;
            this.follows = follows;
            this.projectType = projectType == null ? "" : projectType;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public String getAuthor() {
            return author;
        }

        public String getIconUrl() {
            return iconUrl;
        }

        public long getDownloads() {
            return downloads;
        }

        public long getFollows() {
            return follows;
        }

        public String getProjectType() {
            return projectType;
        }

        @Override
        public String toString() {
            return title + (author == null || author.isBlank() ? "" : " / " + author)
                    + "    下载 " + TextUtil.formatCount(downloads);
        }
    }

    public record ProjectVersion(String versionId, String name, String versionNumber, String versionType) {
        @Override
        public String toString() {
            String displayVersion = versionNumber == null || versionNumber.isBlank()
                    ? versionId : versionNumber;
            String displayName = name == null || name.isBlank() || name.equals(displayVersion)
                    ? "" : name + " · ";
            String displayType = versionType == null || versionType.isBlank()
                    ? "" : " · " + versionType;
            return displayName + displayVersion + displayType;
        }
    }

    public static class DownloadResult {
        private final File mainFile;
        private final List<File> files;

        public DownloadResult(File mainFile, List<File> files) {
            this.mainFile = mainFile;
            this.files = List.copyOf(files);
        }

        public File getMainFile() {
            return mainFile;
        }

        public List<File> getFiles() {
            return files;
        }
    }

    @Override
    public List<Project> searchProjects(String query, String gameVersion, String projectType,
                                        String loader, int limit) throws IOException {
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isBlank()) {
            throw new IOException("请输入要搜索的内容名称");
        }
        return search(trimmedQuery, gameVersion, projectType, loader, ModSearchIndex.RELEVANCE, limit);
    }

    @Override
    public List<Project> listOfficialProjects(String gameVersion, String projectType,
                                              String loader, int limit) throws IOException {
        return search("", gameVersion, projectType, loader, ModSearchIndex.DOWNLOADS, limit);
    }

    public List<Project> searchMods(String query, String gameVersion, String loader, int limit)
            throws IOException {
        return searchProjects(query, gameVersion, "mod", loader, limit);
    }

    private List<Project> search(String query, String gameVersion, String projectType, String loader,
                                 ModSearchIndex index, int limit) throws IOException {
        requireText(gameVersion, "请先选择游戏版本");
        requireText(projectType, "内容类型无效");
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        ModSearchQuery request = new ModSearchQuery(query, gameVersion, loader, projectType,
                Set.of(), index, 0, boundedLimit);
        return await(apiClient.searchMods(request)).hits().stream()
                .map(project -> toProject(project, projectType))
                .toList();
    }

    @Override
    public List<ProjectVersion> listProjectVersions(Project project, String gameVersion, String loader)
            throws IOException {
        requireProject(project);
        List<ModVersion> versions = await(apiClient.getProjectVersions(
                project.getProjectId(), requireText(gameVersion, "请先选择游戏版本"), loader));
        return versions.stream().map(this::toProjectVersion).toList();
    }

    public DownloadResult downloadLatest(Project project, String gameVersion, String loader,
                                         File targetDir, boolean includeRequiredDependencies,
                                         DownloadListener listener, String... allowedExtensions)
            throws IOException {
        requireProject(project);
        List<ProjectVersion> versions = listProjectVersions(project, gameVersion, loader);
        if (versions.isEmpty()) {
            throw new IOException("没有找到兼容 " + gameVersion + " / " + loader + " 的可下载文件");
        }
        return downloadVersion(project, versions.get(0), gameVersion, loader, targetDir,
                includeRequiredDependencies, listener, allowedExtensions);
    }

    public DownloadResult downloadLatest(Project project, String gameVersion, String loader,
                                         File targetDir, DownloadListener listener) throws IOException {
        return downloadLatest(project, gameVersion, loader, targetDir, true, listener, ".jar");
    }

    @Override
    public DownloadResult downloadVersion(Project project, ProjectVersion selectedVersion,
                                          String gameVersion, String loader, File targetDir,
                                          boolean includeRequiredDependencies,
                                          DownloadListener listener, String... allowedExtensions)
            throws IOException {
        requireProject(project);
        if (selectedVersion == null) {
            throw new IOException("请选择项目和具体版本");
        }
        File destination = requireTargetDirectory(targetDir);
        ModVersion version = await(apiClient.getVersion(selectedVersion.versionId()));
        if (!project.getProjectId().equals(version.projectId())) {
            throw new IOException("所选版本不属于当前 Modrinth 项目");
        }

        List<File> files = new ArrayList<>();
        downloadVersion(version, gameVersion, loader, destination, listener,
                new HashSet<>(), new ArrayDeque<>(), files, true,
                includeRequiredDependencies, allowedExtensions);
        if (files.isEmpty()) {
            throw new IOException("这个版本没有可下载文件");
        }
        return new DownloadResult(files.get(0), files);
    }

    private File downloadVersion(ModVersion version, String gameVersion, String loader, File targetDir,
                                 DownloadListener listener, Set<String> visitedVersions,
                                 Deque<String> dependencyPath, List<File> files, boolean primary,
                                 boolean includeRequiredDependencies, String... allowedExtensions)
            throws IOException {
        ensureCompatibleVersion(version, gameVersion, loader);
        String versionId = version.id();
        boolean tracked = versionId != null && !versionId.isBlank();
        if (tracked) {
            ensureNoDependencyCycle(dependencyPath, versionId);
            if (!visitedVersions.add(versionId)) {
                return null;
            }
            dependencyPath.addLast(versionId);
        }

        try {
            ModFile file = selectPrimaryFile(version.files(), allowedExtensions);
            String filename = sanitizeFilename(file.fileName());
            if (file.url() == null || filename == null || filename.isBlank()) {
                throw new IOException("Modrinth 文件信息不完整，无法创建本地文件");
            }

            File target = new File(targetDir, filename);
            String sha1 = file.sha1();
            if (existingFileSatisfies(target, sha1)) {
                notifyStatus(listener, (primary ? "文件已存在，跳过下载: " : "依赖已存在，跳过下载: ")
                        + filename);
            } else {
                notifyStatus(listener, (primary ? "正在下载: " : "正在下载依赖: ") + filename);
                HttpUtil.downloadFileWithProgress(file.url().toString(), target,
                        new HttpUtil.ProgressCallback() {
                            @Override
                            public void onStart(long total) {
                                notifyProgress(listener, 0, total);
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                notifyProgress(listener, downloaded, total);
                            }

                            @Override
                            public void onComplete(File downloaded) {
                                notifyProgress(listener, 1, 1);
                            }
                        });
                if (!sha1.isBlank() && !FileUtil.verifySha1(target, sha1)) {
                    if (!target.delete()) {
                        target.deleteOnExit();
                    }
                    throw new IOException("文件校验失败: " + filename);
                }
            }
            files.add(target);
            if (includeRequiredDependencies) {
                downloadRequiredDependencies(version, gameVersion, loader, targetDir, listener,
                        visitedVersions, dependencyPath, files);
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
                                              List<File> files) throws IOException {
        for (ModDependency dependency : version.dependencies()) {
            if (dependency.type() != DependencyType.REQUIRED) {
                continue;
            }
            ModVersion dependencyVersion = resolveDependency(dependency, gameVersion, loader);
            if (dependencyVersion != null) {
                downloadVersion(dependencyVersion, gameVersion, loader, targetDir, listener,
                        visitedVersions, dependencyPath, files, false, true, ".jar");
            }
        }
    }

    private ModVersion resolveDependency(ModDependency dependency, String gameVersion, String loader)
            throws IOException {
        if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            return await(apiClient.getVersion(dependency.versionId()));
        }
        if (dependency.projectId() == null || dependency.projectId().isBlank()) {
            return null;
        }
        List<ModVersion> versions = await(apiClient.getProjectVersions(
                dependency.projectId(), gameVersion, loader));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private ModFile selectPrimaryFile(List<ModFile> files, String... allowedExtensions)
            throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IOException("这个版本没有可下载文件");
        }
        ModFile fallback = null;
        for (ModFile file : files) {
            if (!isAllowedFilename(file.fileName(), allowedExtensions)) {
                continue;
            }
            if (fallback == null) {
                fallback = file;
            }
            if (file.primary()) {
                return file;
            }
        }
        if (fallback == null) {
            throw new IOException("没有找到可导入的下载文件");
        }
        return fallback;
    }

    private boolean isAllowedFilename(String filename, String... allowedExtensions) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        String[] extensions = allowedExtensions == null || allowedExtensions.length == 0
                ? new String[]{".jar"} : allowedExtensions;
        for (String extension : extensions) {
            if (extension != null && lower.endsWith(extension.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void ensureCompatibleVersion(ModVersion version, String gameVersion, String loader)
            throws IOException {
        if (version == null) {
            throw new IOException("Modrinth 版本信息为空");
        }
        if (gameVersion != null && !gameVersion.isBlank()
                && version.gameVersions().stream().noneMatch(gameVersion::equalsIgnoreCase)) {
            throw new IOException("Modrinth 版本与目标 Minecraft " + gameVersion + " 不兼容");
        }
        if (loader != null && !loader.isBlank()
                && version.loaders().stream().noneMatch(loader::equalsIgnoreCase)) {
            throw new IOException("Modrinth 版本与目标加载器 " + loader + " 不兼容");
        }
    }

    static void ensureNoDependencyCycle(Deque<String> dependencyPath, String versionId)
            throws IOException {
        if (!dependencyPath.contains(versionId)) {
            return;
        }
        List<String> cycle = new ArrayList<>();
        boolean cycleStarted = false;
        for (String current : dependencyPath) {
            if (current.equals(versionId)) {
                cycleStarted = true;
            }
            if (cycleStarted) {
                cycle.add(current);
            }
        }
        cycle.add(versionId);
        throw new IOException("Detected circular Modrinth dependency chain: " + String.join(" -> ", cycle));
    }

    static boolean existingFileSatisfies(File target, String sha1) {
        return target.isFile() && (sha1 == null || sha1.isBlank() || FileUtil.verifySha1(target, sha1));
    }

    private static Project toProject(ModProject project, String projectType) {
        return new Project(project.projectId(), project.slug(), project.title(), project.author(),
                project.description(), project.iconUrl() == null ? null : project.iconUrl().toString(),
                project.downloads(), project.follows(), projectType);
    }

    private ProjectVersion toProjectVersion(ModVersion version) {
        return new ProjectVersion(version.id(), version.name(), version.versionNumber(),
                version.versionType());
    }

    private static File requireTargetDirectory(File targetDir) throws IOException {
        if (targetDir == null) {
            throw new IOException("导入目录无效");
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("无法创建导入目录: " + targetDir.getAbsolutePath());
        }
        if (!targetDir.isDirectory()) {
            throw new IOException("导入目录无效: " + targetDir.getAbsolutePath());
        }
        return targetDir;
    }

    private static void requireProject(Project project) throws IOException {
        if (project == null) {
            throw new IOException("请选择一个下载项目");
        }
    }

    private static String requireText(String value, String message) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(message);
        }
        return value.trim();
    }

    private static String sanitizeFilename(String filename) {
        return filename == null ? null : TextUtil.replaceInvalidFilenameChars(filename);
    }

    private static void notifyStatus(DownloadListener listener, String message) {
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private static void notifyProgress(DownloadListener listener, long downloaded, long total) {
        if (listener != null) {
            listener.onProgress(downloaded, total);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            String message = cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new IOException(message, cause);
        }
    }
}
