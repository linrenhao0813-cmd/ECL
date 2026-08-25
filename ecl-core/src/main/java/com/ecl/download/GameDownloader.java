package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.RuleEvaluator;
import com.ecl.util.ThreadFactories;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameDownloader implements DownloadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameDownloader.class);
    private static final long MAX_GAME_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024;

    public interface DownloadListener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
        void onError(String message);
        void onComplete();
    }

    private final ExecutorService versionDownloadExecutor;
    private final ExecutorService fileDownloadExecutor;
    private final GameDownloadBatchExecutor batchExecutor;
    private final AtomicReference<Future<?>> activeDownload = new AtomicReference<>();
    private volatile DownloadListener configuredListener;
    private volatile boolean verifyExistingFiles = true;

    public GameDownloader() {
        this(ECLConfig.DOWNLOAD_THREADS);
    }

    public GameDownloader(int downloadThreads) {
        versionDownloadExecutor = Executors.newSingleThreadExecutor(
                ThreadFactories.daemon("ecl-version-download"));
        fileDownloadExecutor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(8, downloadThreads)),
                ThreadFactories.daemon("ecl-file-download"));
        batchExecutor = new GameDownloadBatchExecutor(fileDownloadExecutor);
    }

    public void setListener(DownloadListener listener) {
        this.configuredListener = listener;
    }

    /**
     * Controls whether existing libraries and assets are re-hashed before launch.
     * Newly downloaded files are always verified when the version metadata supplies a SHA-1.
     */
    public void setVerifyExistingFiles(boolean verifyExistingFiles) {
        this.verifyExistingFiles = verifyExistingFiles;
    }

    public void downloadVersion(String versionId, String versionUrl) {
        downloadVersionAsync(versionId, versionUrl);
    }

    public synchronized Future<?> downloadVersionAsync(String versionId, String versionUrl) {
        cancelDownload();
        DownloadListener runListener = configuredListener;
        Future<?> task = versionDownloadExecutor.submit(
                () -> {
                    downloadVersionInternal(versionId, versionUrl, runListener);
                    return null;
                });
        activeDownload.set(task);
        return task;
    }

    public synchronized boolean cancelDownload() {
        Future<?> task = activeDownload.getAndSet(null);
        return task != null && task.cancel(true);
    }

    public boolean isDownloadInProgress() {
        Future<?> task = activeDownload.get();
        return task != null && !task.isDone();
    }

    @Override
    public void close() {
        cancelDownload();
        versionDownloadExecutor.shutdownNow();
        fileDownloadExecutor.shutdownNow();
        awaitTermination(versionDownloadExecutor, "version download");
        awaitTermination(fileDownloadExecutor, "file download");
    }

    private void downloadVersionInternal(String versionId, String versionUrl,
                                         DownloadListener runListener) throws Exception {
        try {
            if (runListener != null) runListener.onStatus("正在下载版本信息...");

            File versionDir = FileUtil.safeVersionDirectory(ECLConfig.getVersionsDir(), versionId);
            versionDir.mkdirs();

            JsonObject versionJson = HttpUtil.getJson(versionUrl);
            File versionJsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
            HttpUtil.writeJson(versionJsonFile, versionJson);
            checkCancelled();

            JsonObject downloads = versionJson.has("downloads") ? versionJson.getAsJsonObject("downloads") : null;
            JsonObject client = downloads != null && downloads.has("client")
                    ? downloads.getAsJsonObject("client") : null;
            if (client != null) {
                if (runListener != null) runListener.onStatus("正在下载游戏主文件...");
                String clientUrl = client.get("url").getAsString();
                String clientSha1 = InstallHelpers.requireSha1(
                        client.has("sha1") ? client.get("sha1").getAsString() : null,
                        "Minecraft client");
                long clientSize = requiredPositiveSize(client, "size", "Minecraft client");
                File clientJar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), versionId);
                if (needsDownload(clientJar, clientSha1)) {
                    HttpUtil.downloadFileWithProgress(clientUrl, clientJar, new HttpUtil.ProgressCallback() {
                        @Override
                        public void onStart(long total) {
                            if (runListener != null) runListener.onProgress(0, total);
                        }

                        @Override
                        public void onProgress(long downloaded, long total) {
                            if (runListener != null) runListener.onProgress(downloaded, total);
                        }

                        @Override
                        public void onComplete(File file) {}
                    }, sourceCallback("游戏主文件", runListener), clientSize);
                    if (clientJar.length() != clientSize) {
                        Files.deleteIfExists(clientJar.toPath());
                        throw new IOException("Minecraft client size does not match metadata");
                    }
                    verifyDownloadedFile(clientJar, clientSha1);
                }
            } else if (!hasUsableInheritedClient(versionJson)) {
                throw new IOException("版本缺少 client 下载信息，且继承版本客户端不可用: " + versionId);
            }
            checkCancelled();

            if (runListener != null) runListener.onStatus("正在下载依赖库...");
            downloadLibraries(versionJson, runListener);
            checkCancelled();

            if (runListener != null) runListener.onStatus("正在下载资源文件...");
            downloadAssets(versionJson, runListener);
            checkCancelled();

            if (runListener != null) runListener.onStatus("下载完成！");
            if (runListener != null) runListener.onComplete();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                if (runListener != null) runListener.onStatus("下载已取消");
                throw new CancellationException("download cancelled");
            }
            LOGGER.warn("Game download failed for version {}", versionId, e);
            if (runListener != null) runListener.onError(classifyDownloadError(e));
            // Keep the listener as a UI notification only; the Future must also fail so every
            // caller can observe the download error without relying on callback side effects.
            // Rethrow the original exception (preserving IOException / CancellationException types).
            throw e;
        }
    }

    /**
     * Maps common download failures to fixed Chinese user-facing copy. The underlying root cause
     * is kept in the log by the caller; this only decides what the user sees.
     */
    private static String classifyDownloadError(Exception failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (isNetworkFailure(failure) || lower.contains("connect") || lower.contains("timeout")
                || lower.contains("timed out") || lower.contains("unknownhost")
                || lower.contains("网络") || lower.contains("连接")) {
            return "网络连接失败，请检查网络连接后重试。";
        }
        if (lower.contains("sha-1") || lower.contains("sha1") || lower.contains("hash")
                || lower.contains("checksum") || lower.contains("size does not match")
                || lower.contains("校验") || lower.contains("哈希")) {
            return "下载文件校验失败，请重试下载。";
        }
        if (lower.contains("mirror") || lower.contains("镜像") || lower.contains("下载源")
                || lower.contains("source")) {
            return "下载源不可用，请稍后重试。";
        }
        return "下载失败: " + message;
    }

    private static boolean isNetworkFailure(Exception failure) {
        return failure instanceof java.net.ConnectException
                || failure instanceof java.net.UnknownHostException
                || failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.net.http.HttpTimeoutException
                || failure instanceof javax.net.ssl.SSLException;
    }

    private void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("download cancelled");
    }

    private void awaitTermination(ExecutorService executor, String executorName) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while stopping " + executorName + " executor");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping " + executorName + " executor", e);
        }
    }

    private boolean hasUsableInheritedClient(JsonObject versionJson) {
        if (!versionJson.has("inheritsFrom")) return false;
        String current = versionJson.get("inheritsFrom").getAsString();
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            File jar;
            File jsonFile;
            try {
                jar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), current);
                jsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), current);
            } catch (IOException e) {
                return false;
            }
            if (jar.isFile()) return true;
            if (!jsonFile.isFile()) return false;
            try {
                JsonObject parentJson = HttpUtil.readJson(jsonFile);
                if (parentJson.has("jar")) {
                    String jarId = parentJson.get("jar").getAsString();
                    return FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), jarId).isFile();
                }
                current = parentJson.has("inheritsFrom")
                        ? parentJson.get("inheritsFrom").getAsString() : null;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private void downloadLibraries(JsonObject versionJson, DownloadListener runListener) throws IOException {
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries == null) return;
        List<String> missingLibraries = getMissingLibraries(versionJson);
        if (!missingLibraries.isEmpty() && runListener != null) {
            runListener.onStatus("检测到 " + missingLibraries.size() + " 个缺失的依赖库");
        }
        NativePlatform nativePlatform = NativePlatform.current();
        List<GameDownloadBatchExecutor.DownloadTask> tasks = new ArrayList<>();

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();

            if (lib.has("rules") && !RuleEvaluator.isAllowed(lib.getAsJsonArray("rules"))) {
                continue;
            }

            JsonObject artifacts = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            if (artifacts != null && artifacts.has("artifact")) {
                JsonObject artifact = artifacts.getAsJsonObject("artifact");
                addDownloadIfNeeded(tasks, artifact, "依赖库");
            } else if (artifacts == null) {
                // Fabric/Quilt style: bare Maven coordinate with a repository URL.
                String name = lib.has("name") ? lib.get("name").getAsString() : "";
                String repository = lib.has("url") ? lib.get("url").getAsString() : "";
                if (MavenCoordinates.isSimpleCoordinate(name) && !repository.isBlank()) {
                    JsonObject artifact = new JsonObject();
                    String resolvedUrl = MavenCoordinates.repositoryUrl(repository, name);
                    artifact.addProperty("path", MavenCoordinates.repositoryPath(name));
                    artifact.addProperty("url", resolvedUrl);
                    artifact.addProperty("sha1", InstallHelpers.resolveRemoteSha1(
                            resolvedUrl, "Maven library " + name));
                    addDownloadIfNeeded(tasks, artifact, "依赖库");
                }
            }

            if (artifacts != null && artifacts.has("classifiers")) {
                JsonObject classifiers = artifacts.getAsJsonObject("classifiers");
                String nativeKey = nativeClassifierKey(lib, classifiers, nativePlatform.osName(),
                        nativePlatform.archBits(), nativePlatform.nativeClassifier());
                if (nativeKey != null) {
                    addDownloadIfNeeded(tasks, classifiers.getAsJsonObject(nativeKey), "原生库");
                }
            }
        }

        batchExecutor.download(tasks, "依赖库", runListener);
    }

    private void addDownloadIfNeeded(
            List<GameDownloadBatchExecutor.DownloadTask> tasks,
            JsonObject artifact, String sourceLabel) throws IOException {
        String url = requiredString(artifact, "url", sourceLabel + " URL");
        String path = requiredString(artifact, "path", sourceLabel + " path");
        String sha1 = InstallHelpers.requireSha1(
                artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
                sourceLabel + " " + path);
        long size = artifact.has("size") ? artifact.get("size").getAsLong() : -1L;
        File target = FileUtil.safeResolveUnder(ECLConfig.getLibrariesDir(), path);
        if (needsDownload(target, sha1)) {
            tasks.add(new GameDownloadBatchExecutor.DownloadTask(
                    url, target, sha1, size, sourceLabel));
        }
    }

    private void downloadAssets(JsonObject versionJson, DownloadListener runListener) throws IOException {
        JsonElement assetIndexElement = versionJson.get("assetIndex");
        if (assetIndexElement == null || !assetIndexElement.isJsonObject()) return;
        JsonObject assetIndex = assetIndexElement.getAsJsonObject();

        String assetId = requiredString(assetIndex, "id", "asset index id");
        FileUtil.requireSafeVersionId(assetId);
        String assetUrl = requiredString(assetIndex, "url", "asset index URL");
        File assetDir = new File(ECLConfig.getAssetsDir(), "objects");
        File indexFile = FileUtil.safeResolveUnder(ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");

        String indexSha1 = InstallHelpers.requireSha1(
                assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null,
                "asset index " + assetId);
        long indexSize = requiredPositiveSize(assetIndex, "size", "asset index " + assetId);
        if (needsDownload(indexFile, indexSha1)) {
            indexFile.getParentFile().mkdirs();
            HttpUtil.downloadFileWithProgress(assetUrl, indexFile, null,
                    sourceCallback("资源索引", runListener), indexSize);
            if (indexFile.length() != indexSize) {
                Files.deleteIfExists(indexFile.toPath());
                throw new IOException("Asset index size does not match metadata: " + assetId);
            }
            verifyDownloadedFile(indexFile, indexSha1);
        }

        // 该资源索引上次已完整校验（index SHA-1 未变）时跳过对每个文件的重复哈希计算，
        // 只做存在性检查；索引变更或缺失校验标记时才执行全量 SHA-1 校验。
        boolean skipHashVerification = verifiedMarkerMatches(assetId, indexSha1);

        JsonObject objects = HttpUtil.readJson(indexFile).getAsJsonObject("objects");
        List<GameDownloadBatchExecutor.DownloadTask> tasks = new ArrayList<>();
        for (String name : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(name);
            String hash = obj.get("hash").getAsString();
            if (!hash.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("资源对象哈希无效: " + hash);
            }
            String subPath = hash.substring(0, 2) + "/" + hash;
            long size = requiredPositiveSize(obj, "size", "asset object " + name);
            File target = FileUtil.safeResolveUnder(assetDir, subPath);
            if (needsDownload(target, hash, skipHashVerification)) {
                tasks.add(new GameDownloadBatchExecutor.DownloadTask(
                        "https://resources.download.minecraft.net/" + subPath,
                        target, hash, size, "资源文件"));
            }
        }

        batchExecutor.download(tasks, "资源文件", runListener);
        // 全部下载成功（或本就没有缺失）后记录校验标记，下次启动免去全量哈希。
        writeVerifiedMarker(assetId, indexSha1);
    }

    /** 校验标记：<assets>/.ecl-verified-indexes/<assetId>.marker，内容为该索引的 SHA-1。 */
    private File verifiedMarkerFile(String assetId) {
        return new File(ECLConfig.getAssetsDir(), ".ecl-verified-indexes/" + assetId + ".marker");
    }

    private boolean verifiedMarkerMatches(String assetId, String indexSha1) {
        if (!verifyExistingFiles || !hasSha1(indexSha1)) {
            return false;
        }
        File marker = verifiedMarkerFile(assetId);
        if (!marker.isFile()) {
            return false;
        }
        try {
            return indexSha1.equalsIgnoreCase(
                    Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            LOGGER.debug("Failed to read assets verification marker {}", marker, e);
            return false;
        }
    }

    private void writeVerifiedMarker(String assetId, String indexSha1) {
        if (!hasSha1(indexSha1)) {
            return;
        }
        File marker = verifiedMarkerFile(assetId);
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        try {
            Files.writeString(marker.toPath(), indexSha1, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.debug("Failed to write assets verification marker {}", marker, e);
        }
    }

    boolean needsDownload(File target, String expectedSha1) {
        return needsDownload(target, expectedSha1, false);
    }

    boolean needsDownload(File target, String expectedSha1, boolean skipHashVerification) {
        if (skipHashVerification) {
            return !target.isFile();
        }
        return InstallHelpers.needsDownload(target, expectedSha1, verifyExistingFiles);
    }

    private void verifyDownloadedFile(File target, String expectedSha1) throws IOException {
        InstallHelpers.verifyDownloadedFile(target, expectedSha1);
    }

    private static boolean hasSha1(String sha1) {
        return InstallHelpers.hasSha1(sha1);
    }

    private static String requiredString(JsonObject object, String key, String description)
            throws IOException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            throw new IOException("Missing or invalid " + description);
        }
        try {
            String value = object.get(key).getAsString();
            if (value == null || value.isBlank()) {
                throw new IOException("Missing or blank " + description);
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid " + description, invalid);
        }
    }

    private static long requiredPositiveSize(JsonObject object, String key, String description)
            throws IOException {
        try {
            long value = object != null && object.has(key) ? object.get(key).getAsLong() : -1L;
            if (value <= 0 || value > MAX_GAME_ARTIFACT_BYTES) {
                throw new IOException("Missing or invalid " + description + " size");
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid " + description + " size", invalid);
        }
    }

    static String nativeClassifierKey(JsonObject library, String osName, String archBits) {
        return InstallHelpers.nativeClassifierKey(library, osName, archBits);
    }

    static String nativeClassifierKey(JsonObject library, JsonObject classifiers, String osName,
                                      String archBits, String nativeClassifier) {
        return InstallHelpers.nativeClassifierKey(library, classifiers, osName, archBits,
                nativeClassifier);
    }

    private HttpUtil.SourceCallback sourceCallback(String label, DownloadListener runListener) {
        return new HttpUtil.SourceCallback() {
            @Override
            public void onSource(String originalUrl, String candidateUrl, boolean mirror, String sourceName) {
                if (runListener != null && mirror) {
                    runListener.onStatus(label + "官方源响应较慢，切换到" + sourceName + "...");
                }
            }

            @Override
            public void onFailure(String candidateUrl, IOException error) {
                if (runListener != null) {
                    runListener.onStatus(label + "下载源失败，尝试下一个源: " + error.getMessage());
                }
            }
        };
    }

    public List<String> getMissingLibraries(JsonObject versionJson) {
        List<String> missing = new ArrayList<>();
        if (versionJson == null || !versionJson.has("libraries")) return missing;
        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("rules") && !RuleEvaluator.isAllowed(lib.getAsJsonArray("rules"))) {
                continue;
            }
            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    String path = artifact.get("path").getAsString();
                    File target = safeLibraryTarget(path);
                    if (target != null && !target.exists()) {
                        missing.add(lib.has("name") ? lib.get("name").getAsString() : path);
                    }
                }
            } else {
                String name = lib.has("name") ? lib.get("name").getAsString() : "";
                String repository = lib.has("url") ? lib.get("url").getAsString() : "";
                if (MavenCoordinates.isSimpleCoordinate(name) && !repository.isBlank()) {
                    File target = safeLibraryTarget(MavenCoordinates.repositoryPath(name));
                    if (target != null && !target.exists()) {
                        missing.add(name);
                    }
                }
            }
        }
        return missing;
    }

    /** Resolves a library path inside the libraries dir, or null when the path escapes it. */
    private static File safeLibraryTarget(String path) {
        try {
            return FileUtil.safeResolveUnder(ECLConfig.getLibrariesDir(), path);
        } catch (IOException e) {
            return null;
        }
    }

    private record NativePlatform(String osName, String archBits, String nativeClassifier) {
        private static NativePlatform current() {
            String architecture = System.getProperty("os.arch", "").toLowerCase();
            String bits = architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
            String osName = "windows";
            return new NativePlatform(osName, bits,
                    osName + "-" + FileUtil.nativeArchitecture(architecture));
        }
    }
}
