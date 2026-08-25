package com.ecl.download;

import com.ecl.curseforge.CurseForgeApiClient;
import com.ecl.curseforge.CurseForgeApiClient.ApiDependency;
import com.ecl.curseforge.CurseForgeApiClient.ApiFile;
import com.ecl.curseforge.CurseForgeApiClient.ApiProject;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.download.ModrinthDownloader;
import com.ecl.modrinth.model.ContentDownloadResult;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.NetworkUriPolicy;
import com.ecl.util.TextUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** CurseForge implementation of the content downloader used by all four library categories. */
public final class CurseForgeDownloader implements ContentDownloader {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final long MAX_OVERRIDE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_CONTENT_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private final CurseForgeApiClient api;

    public CurseForgeDownloader(Supplier<String> apiKeySupplier) {
        this(new CurseForgeApiClient(apiKeySupplier));
    }

    public CurseForgeDownloader(CurseForgeApiClient api) {
        this.api = api;
    }

    public CurseForgeApiClient api() {
        return api;
    }

    public boolean isConfigured() {
        return api.isConfigured();
    }

    @Override
    public List<ContentProject> searchProjects(
            String query, String gameVersion, String projectType, String loader, int limit)
            throws IOException {
        if (query == null || query.isBlank()) throw new IOException("请输入要搜索的内容名称");
        return projects(api.search(query, gameVersion, projectType, loader, 0, limit, false).projects());
    }

    @Override
    public List<ContentProject> listOfficialProjects(
            String gameVersion, String projectType, String loader, int limit) throws IOException {
        return projects(api.search("", gameVersion, projectType, loader, 0, limit, true).projects());
    }

    @Override
    public List<ContentVersion> listProjectVersions(
            ContentProject project, String gameVersion, String loader) throws IOException {
        requireProject(project);
        return api.getFiles(project.getProjectId(), gameVersion, loader).stream()
                .map(file -> new ContentVersion(
                        versionId(file), file.displayName(), file.displayName(), file.releaseType()))
                .toList();
    }

    @Override
    public ContentDownloadResult downloadVersion(
            ContentProject project,
            ContentVersion selectedVersion,
            String gameVersion,
            String loader,
            File targetDir,
            boolean includeRequiredDependencies,
            ModrinthDownloader.DownloadListener listener,
            String... allowedExtensions
    ) throws IOException {
        requireProject(project);
        if (selectedVersion == null) throw new IOException("请选择具体版本");
        if (targetDir == null) throw new IOException("下载目录无效");
        Files.createDirectories(targetDir.toPath());
        String[] ids = splitVersionId(selectedVersion.versionId());
        if (!project.getProjectId().equals(ids[0])) {
            throw new IOException("所选文件不属于当前 CurseForge 项目");
        }
        ApiFile file = api.getFile(ids[0], ids[1]);
        List<File> downloaded = new ArrayList<>();
        File main = downloadFile(file, targetDir, true, listener, allowedExtensions);
        downloaded.add(main);
        if (includeRequiredDependencies && "mod".equals(project.getProjectType())) {
            downloadDependencies(file, gameVersion, loader, targetDir, listener,
                    new HashSet<>(Set.of(versionId(file))), downloaded);
        }
        return new ContentDownloadResult(main, List.copyOf(downloaded));
    }

    private void downloadDependencies(ApiFile parent, String gameVersion, String loader, File targetDir,
                                      ModrinthDownloader.DownloadListener listener,
                                      Set<String> visited, List<File> downloaded) throws IOException {
        for (ApiDependency dependency : parent.dependencies()) {
            if (dependency.relationType() != 3 || "0".equals(dependency.projectId())) continue;
            List<ApiFile> candidates = api.getFiles(dependency.projectId(), gameVersion, loader);
            if (candidates.isEmpty()) {
                throw new IOException("CurseForge 必需依赖没有兼容文件: " + dependency.projectId());
            }
            ApiFile file = selectDependencyFile(candidates);
            if (!visited.add(versionId(file))) continue;
            downloaded.add(downloadFile(file, targetDir, false, listener, ".jar"));
            downloadDependencies(file, gameVersion, loader, targetDir, listener, visited, downloaded);
        }
    }

    private static ApiFile selectDependencyFile(List<ApiFile> candidates) throws IOException {
        return candidates.stream()
                .filter(file -> file.fileName() != null
                        && file.fileName().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .min(Comparator.comparingInt(CurseForgeDownloader::releaseRank)
                        .thenComparing(file -> file.publishedAt() == null
                                        ? java.time.Instant.EPOCH : file.publishedAt(),
                                Comparator.reverseOrder()))
                .orElseThrow(() -> new IOException("CurseForge 必需依赖没有可下载的 JAR 文件"));
    }

    private static int releaseRank(ApiFile file) {
        return switch (file.releaseType() == null ? "" : file.releaseType().toLowerCase(Locale.ROOT)) {
            case "release" -> 0;
            case "beta" -> 1;
            case "alpha" -> 2;
            default -> 3;
        };
    }

    private File downloadFile(ApiFile file, File targetDir, boolean primary,
                              ModrinthDownloader.DownloadListener listener,
                              String... allowedExtensions) throws IOException {
        String fileName = sanitizeFilename(file.fileName());
        boolean modpackZip = fileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                && extensionConfigured(".mrpack", allowedExtensions);
        if (!modpackZip && !extensionAllowed(fileName, allowedExtensions)) {
            throw new IOException("CurseForge 文件类型不受支持: " + fileName);
        }
        File target = new File(targetDir, fileName);
        String sha1 = file.hashes().getOrDefault("sha1", "");
        if (!HashVerifier.hasUsableExpectedHash(file.hashes())) {
            throw new IOException("CurseForge 文件缺少 SHA-1 校验值: " + fileName);
        }
        if (file.size() <= 0 || file.size() > MAX_CONTENT_FILE_BYTES) {
            throw new IOException("CurseForge 文件大小声明无效: " + fileName);
        }
        if (target.isFile() && (!sha1.isBlank() && FileUtil.verifySha1(target, sha1))) {
            notifyStatus(listener, "文件已存在，跳过下载: " + fileName);
            return target;
        }
        notifyStatus(listener, (primary ? "正在下载: " : "正在下载依赖: ") + fileName);
        java.net.URI downloadUri;
        try {
            downloadUri = NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                    java.net.URI.create(api.getDownloadUrl(file)), "CurseForge 下载地址");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("CurseForge 下载地址无效: " + fileName, invalid);
        }
        HttpUtil.downloadFileWithProgress(downloadUri.toString(), target,
                new HttpUtil.ProgressCallback() {
                    @Override public void onStart(long total) { notifyProgress(listener, 0, total); }
                    @Override public void onProgress(long downloaded, long total) {
                        notifyProgress(listener, downloaded, total);
                    }
                    @Override public void onComplete(File value) { notifyProgress(listener, 1, 1); }
                }, null, file.size());
        if (target.length() != file.size()) {
            Files.deleteIfExists(target.toPath());
            throw new IOException("CurseForge 文件大小校验失败: " + fileName);
        }
        try {
            new HashVerifier().verify(target.toPath(), file.hashes());
        } catch (RuntimeException invalid) {
            Files.deleteIfExists(target.toPath());
            throw new IOException("CurseForge 文件校验失败: " + fileName, invalid);
        }
        return target;
    }

    /** Converts a CurseForge manifest pack to MRPACK so the existing transactional installer can install it. */
    public File convertModpackToMrpack(File archive) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("CurseForge 整合包文件不存在");
        Path output = Files.createTempFile("ecl-curseforge-pack-", ".mrpack");
        try (ZipFile input = new ZipFile(archive, StandardCharsets.UTF_8);
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            JsonObject manifest = readManifest(input);
            JsonObject index = createMrpackIndex(manifest);
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(index.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            copyOverrides(input, zip, JsonUtil.getString(manifest, "overrides", "overrides"));
            return output.toFile();
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(output);
            if (error instanceof IOException io) throw io;
            throw new IOException("CurseForge 整合包 manifest.json 无效", error);
        }
    }

    private JsonObject createMrpackIndex(JsonObject manifest) throws IOException {
        JsonObject minecraft = manifest.has("minecraft") && manifest.get("minecraft").isJsonObject()
                ? manifest.getAsJsonObject("minecraft") : null;
        if (minecraft == null) throw new IOException("CurseForge 整合包缺少 Minecraft 版本");
        String minecraftVersion = JsonUtil.getString(minecraft, "version", "");
        if (minecraftVersion.isBlank()) throw new IOException("CurseForge 整合包未声明 Minecraft 版本");
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", JsonUtil.getString(manifest, "version", "1"));
        index.addProperty("name", JsonUtil.getString(manifest, "name", "CurseForge Pack"));
        index.addProperty("summary", "Converted from a CurseForge manifest by ECL");
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", minecraftVersion);
        for (JsonElement element : array(minecraft, "modLoaders")) {
            if (!element.isJsonObject()) continue;
            String id = JsonUtil.getString(element.getAsJsonObject(), "id", "");
            addLoaderDependency(dependencies, id);
            if (dependencies.size() > 1) break;
        }
        index.add("dependencies", dependencies);
        JsonArray files = new JsonArray();
        for (JsonElement element : array(manifest, "files")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String projectId = Integer.toString(JsonUtil.getInt(item, "projectID", 0));
            String fileId = Integer.toString(JsonUtil.getInt(item, "fileID", 0));
            ApiFile remote = api.getFile(projectId, fileId);
            JsonObject converted = new JsonObject();
            converted.addProperty("path", "mods/" + sanitizeFilename(remote.fileName()));
            converted.addProperty("fileSize", remote.size());
            JsonObject hashes = new JsonObject();
            remote.hashes().forEach(hashes::addProperty);
            converted.add("hashes", hashes);
            JsonArray downloads = new JsonArray();
            downloads.add(api.getDownloadUrl(remote));
            converted.add("downloads", downloads);
            files.add(converted);
        }
        index.add("files", files);
        return index;
    }

    private static void addLoaderDependency(JsonObject dependencies, String loaderId) {
        if (loaderId == null || loaderId.isBlank()) return;
        String lower = loaderId.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("neoforge-", "forge-", "fabric-", "quilt-")) {
            if (!lower.startsWith(prefix)) continue;
            String key = switch (prefix) {
                case "fabric-" -> "fabric-loader";
                case "quilt-" -> "quilt-loader";
                default -> prefix.substring(0, prefix.length() - 1);
            };
            dependencies.addProperty(key, loaderId.substring(prefix.length()));
            return;
        }
    }

    private static JsonObject readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null || entry.getSize() > MAX_MANIFEST_BYTES) {
            throw new IOException("CurseForge 整合包缺少 manifest.json 或清单过大");
        }
        try (InputStream stream = zip.getInputStream(entry)) {
            byte[] data = stream.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (data.length > MAX_MANIFEST_BYTES) throw new IOException("CurseForge 清单过大");
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void copyOverrides(ZipFile input, ZipOutputStream output, String configuredPrefix)
            throws IOException {
        String prefix = (configuredPrefix == null || configuredPrefix.isBlank()
                ? "overrides" : configuredPrefix).replace('\\', '/').replaceAll("^/+|/+$", "") + "/";
        long total = 0;
        var entries = input.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/');
            if (entry.isDirectory() || !name.startsWith(prefix)) continue;
            String relative = name.substring(prefix.length());
            if (relative.isBlank() || relative.startsWith("/") || relative.contains("../")) {
                throw new IOException("CurseForge overrides 包含不安全路径: " + name);
            }
            long declared = entry.getSize();
            if (declared > MAX_OVERRIDE_BYTES - total) throw new IOException("CurseForge overrides 过大");
            output.putNextEntry(new ZipEntry("overrides/" + relative));
            try (InputStream stream = input.getInputStream(entry)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > MAX_OVERRIDE_BYTES) throw new IOException("CurseForge overrides 过大");
                    output.write(buffer, 0, read);
                }
            }
            output.closeEntry();
        }
    }

    private static List<ContentProject> projects(List<ApiProject> values) {
        return values.stream().map(value -> new ContentProject(
                value.id(), value.slug(), value.title(), value.author(), value.summary(),
                value.iconUrl(), value.downloads(), 0, value.projectType())).toList();
    }

    private static void requireProject(ContentProject project) throws IOException {
        if (project == null) throw new IOException("请选择一个下载项目");
    }

    private static String versionId(ApiFile file) {
        return file.projectId() + ":" + file.id();
    }

    private static String[] splitVersionId(String value) throws IOException {
        String[] parts = value == null ? new String[0] : value.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IOException("CurseForge 文件标识无效: " + value);
        }
        return parts;
    }

    private static String sanitizeFilename(String value) throws IOException {
        String result = TextUtil.replaceInvalidFilenameChars(value == null ? "" : value)
                .replaceAll("[. ]+$", "");
        if (result.isBlank() || result.equals(".") || result.equals("..")) {
            throw new IOException("CurseForge 文件名无效");
        }
        return result;
    }

    private static boolean extensionAllowed(String fileName, String... allowed) {
        if (allowed == null || allowed.length == 0) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : allowed) {
            if (extension != null && lower.endsWith(extension.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean extensionConfigured(String expected, String... allowed) {
        if (allowed == null) return false;
        for (String extension : allowed) {
            if (expected.equalsIgnoreCase(extension)) return true;
        }
        return false;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static void notifyStatus(ModrinthDownloader.DownloadListener listener, String message) {
        if (listener != null) listener.onStatus(message);
    }

    private static void notifyProgress(ModrinthDownloader.DownloadListener listener,
                                       long downloaded, long total) {
        if (listener != null) listener.onProgress(downloaded, total);
    }
}
