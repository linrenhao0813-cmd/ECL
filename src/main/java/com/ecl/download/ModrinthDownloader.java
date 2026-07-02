package com.ecl.download;

import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModrinthDownloader {
    private static final String API_BASE = "https://api.modrinth.com/v2";

    public interface DownloadListener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
    }

    public static class Project {
        private final String projectId;
        private final String slug;
        private final String title;
        private final String author;
        private final String description;
        private final long downloads;
        private final long follows;

        public Project(String projectId, String slug, String title, String author, String description, long downloads, long follows) {
            this.projectId = projectId;
            this.slug = slug;
            this.title = title;
            this.author = author;
            this.description = description;
            this.downloads = downloads;
            this.follows = follows;
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

        public long getDownloads() {
            return downloads;
        }

        public long getFollows() {
            return follows;
        }

        @Override
        public String toString() {
            return title
                    + (author == null || author.isBlank() ? "" : " / " + author)
                    + "    下载 " + formatCount(downloads);
        }
    }

    public static class DownloadResult {
        private final File mainFile;
        private final List<File> files;

        public DownloadResult(File mainFile, List<File> files) {
            this.mainFile = mainFile;
            this.files = files;
        }

        public File getMainFile() {
            return mainFile;
        }

        public List<File> getFiles() {
            return files;
        }
    }

    public List<Project> searchProjects(String query, String gameVersion, String projectType, String loader, int limit) throws IOException {
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isBlank()) {
            throw new IOException("请输入要搜索的内容名称");
        }
        return fetchProjects(trimmedQuery, gameVersion, projectType, loader, "relevance", limit);
    }

    public List<Project> listOfficialProjects(String gameVersion, String projectType, String loader, int limit) throws IOException {
        return fetchProjects("", gameVersion, projectType, loader, "downloads", limit);
    }

    private List<Project> fetchProjects(String query, String gameVersion, String projectType, String loader, String index, int limit) throws IOException {
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new IOException("请先选择游戏版本");
        }
        if (projectType == null || projectType.isBlank()) {
            throw new IOException("内容类型无效");
        }

        StringBuilder facets = new StringBuilder("[[\"project_type:")
                .append(escapeFacetValue(projectType))
                .append("\"],[\"versions:")
                .append(escapeFacetValue(gameVersion))
                .append("\"]");
        if (loader != null && !loader.isBlank()) {
            facets.append(",[\"categories:")
                    .append(escapeFacetValue(loader))
                    .append("\"]");
        }
        facets.append("]");
        String url = API_BASE + "/search"
                + "?limit=" + Math.max(1, Math.min(limit, 50))
                + "&index=" + encode(index == null || index.isBlank() ? "downloads" : index)
                + "&facets=" + encode(facets.toString());
        if (query != null && !query.isBlank()) {
            url += "&query=" + encode(query.trim());
        }

        JsonObject json = JsonParser.parseString(HttpUtil.get(url)).getAsJsonObject();
        JsonArray hits = json.getAsJsonArray("hits");
        List<Project> projects = new ArrayList<>();
        if (hits == null) {
            return projects;
        }

        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            String projectId = getString(hit, "project_id");
            if (projectId == null || projectId.isBlank()) {
                continue;
            }
            projects.add(new Project(
                    projectId,
                    getString(hit, "slug"),
                    getString(hit, "title"),
                    getString(hit, "author"),
                    getString(hit, "description"),
                    getLong(hit, "downloads"),
                    getLong(hit, "follows")
            ));
        }
        return projects;
    }

    public List<Project> searchMods(String query, String gameVersion, String loader, int limit) throws IOException {
        return searchProjects(query, gameVersion, "mod", loader, limit);
    }

    public DownloadResult downloadLatest(Project project, String gameVersion, String loader, File targetDir,
                                         boolean includeRequiredDependencies, DownloadListener listener,
                                         String... allowedExtensions) throws IOException {
        if (project == null) {
            throw new IOException("请选择一个下载项目");
        }
        if (targetDir == null) {
            throw new IOException("导入目录无效");
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("无法创建导入目录: " + targetDir.getAbsolutePath());
        }

        List<File> files = new ArrayList<>();
        Set<String> visitedVersions = new HashSet<>();
        JsonObject version = findLatestVersion(project.getProjectId(), gameVersion, loader);
        File mainFile = downloadVersion(version, gameVersion, loader, targetDir, listener, visitedVersions, files,
                true, includeRequiredDependencies, allowedExtensions);
        return new DownloadResult(mainFile, files);
    }

    public DownloadResult downloadLatest(Project project, String gameVersion, String loader, File modsDir, DownloadListener listener) throws IOException {
        return downloadLatest(project, gameVersion, loader, modsDir, true, listener, ".jar");
    }

    private File downloadVersion(JsonObject version, String gameVersion, String loader, File targetDir, DownloadListener listener,
                                 Set<String> visitedVersions, List<File> files, boolean primary,
                                 boolean includeRequiredDependencies, String... allowedExtensions) throws IOException {
        String versionId = getString(version, "id");
        if (versionId != null && !visitedVersions.add(versionId)) {
            return null;
        }

        JsonObject fileJson = selectPrimaryFile(version, allowedExtensions);
        String url = getString(fileJson, "url");
        String filename = sanitizeFilename(getString(fileJson, "filename"));
        if (url == null || url.isBlank() || filename == null || filename.isBlank()) {
            throw new IOException("Modrinth 文件信息不完整");
        }

        File target = new File(targetDir, filename);
        String sha1 = null;
        if (fileJson.has("hashes") && fileJson.get("hashes").isJsonObject()) {
            sha1 = getString(fileJson.getAsJsonObject("hashes"), "sha1");
        }

        if (target.exists() && (sha1 == null || FileUtil.verifySha1(target, sha1))) {
            notifyStatus(listener, (primary ? "模组已存在，跳过下载: " : "依赖已存在，跳过下载: ") + filename);
        } else {
            notifyStatus(listener, (primary ? "正在下载模组: " : "正在下载依赖: ") + filename);
            HttpUtil.downloadFileWithProgress(url, target, new HttpUtil.ProgressCallback() {
                @Override
                public void onStart(long total) {
                    notifyProgress(listener, 0, total);
                }

                @Override
                public void onProgress(long downloaded, long total) {
                    notifyProgress(listener, downloaded, total);
                }

                @Override
                public void onComplete(File file) {
                    notifyProgress(listener, 1, 1);
                }
            });

            if (sha1 != null && !FileUtil.verifySha1(target, sha1)) {
                target.delete();
                throw new IOException("模组文件校验失败: " + filename);
            }
        }

        files.add(target);
        if (includeRequiredDependencies) {
            downloadRequiredDependencies(version, gameVersion, loader, targetDir, listener, visitedVersions, files);
        }
        return target;
    }

    private void downloadRequiredDependencies(JsonObject version, String gameVersion, String loader, File modsDir, DownloadListener listener,
                                              Set<String> visitedVersions, List<File> files) throws IOException {
        if (!version.has("dependencies") || !version.get("dependencies").isJsonArray()) {
            return;
        }

        for (JsonElement element : version.getAsJsonArray("dependencies")) {
            JsonObject dependency = element.getAsJsonObject();
            if (!"required".equals(getString(dependency, "dependency_type"))) {
                continue;
            }

            JsonObject dependencyVersion = null;
            String dependencyVersionId = getString(dependency, "version_id");
            if (dependencyVersionId != null && !dependencyVersionId.isBlank()) {
                dependencyVersion = getVersion(dependencyVersionId);
            } else {
                String dependencyProjectId = getString(dependency, "project_id");
                if (dependencyProjectId != null && !dependencyProjectId.isBlank()) {
                    dependencyVersion = findLatestVersion(dependencyProjectId, gameVersion, loader);
                }
            }

            if (dependencyVersion != null) {
                downloadVersion(dependencyVersion, gameVersion, loader, modsDir, listener, visitedVersions, files, false, true, ".jar");
            }
        }
    }

    private JsonObject findLatestVersion(String projectId, String gameVersion, String loader) throws IOException {
        String url = API_BASE + "/project/" + encodePath(projectId) + "/version"
                + "?game_versions=" + encodeJsonArray(gameVersion);
        if (loader != null && !loader.isBlank()) {
            url += "&loaders=" + encodeJsonArray(loader);
        }

        JsonArray versions = JsonParser.parseString(HttpUtil.get(url)).getAsJsonArray();
        if (versions.isEmpty()) {
            throw new IOException("没有找到兼容 " + gameVersion + " / " + loader + " 的可下载文件");
        }
        return versions.get(0).getAsJsonObject();
    }

    private JsonObject getVersion(String versionId) throws IOException {
        String url = API_BASE + "/version/" + encodePath(versionId);
        return JsonParser.parseString(HttpUtil.get(url)).getAsJsonObject();
    }

    private JsonObject selectPrimaryFile(JsonObject version, String... allowedExtensions) throws IOException {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            throw new IOException("这个版本没有可下载文件");
        }

        JsonObject fallback = null;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String filename = getString(file, "filename");
            if (!isAllowedFilename(filename, allowedExtensions)) {
                continue;
            }
            if (fallback == null) {
                fallback = file;
            }
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                return file;
            }
        }

        if (fallback != null) {
            return fallback;
        }
        throw new IOException("没有找到可导入的下载文件");
    }

    private boolean isAllowedFilename(String filename, String... allowedExtensions) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase();
        String[] extensions = allowedExtensions == null || allowedExtensions.length == 0
                ? new String[]{".jar"}
                : allowedExtensions;
        for (String extension : extensions) {
            if (extension != null && lower.endsWith(extension.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void notifyStatus(DownloadListener listener, String message) {
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private void notifyProgress(DownloadListener listener, long downloaded, long total) {
        if (listener != null) {
            listener.onProgress(downloaded, total);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return null;
        }
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String escapeFacetValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String encodeJsonArray(String value) {
        return encode("[\"" + escapeFacetValue(value) + "\"]");
    }

    private String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private long getLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String formatCount(long value) {
        if (value >= 100000000) {
            return (value / 100000000) + "亿+";
        }
        if (value >= 10000) {
            return (value / 10000) + "万+";
        }
        return Long.toString(value);
    }
}
