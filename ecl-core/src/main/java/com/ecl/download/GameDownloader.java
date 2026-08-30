package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.util.FileLockLease;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.ManagedLockPaths;
import com.ecl.util.NetworkUriPolicy;
import com.ecl.util.RuleEvaluator;
import com.ecl.util.ThreadFactories;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameDownloader implements DownloadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameDownloader.class);
    private static final int MAX_VERSION_METADATA_BYTES = 4 * 1024 * 1024;
    public interface DownloadListener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
        void onError(String message);
        void onComplete();
    }

    private final ExecutorService versionDownloadExecutor;
    private final ExecutorService fileDownloadExecutor;
    private final GameDownloadBatchExecutor batchExecutor;
    private final GameAssetVerifier assetVerifier;
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
        assetVerifier = new GameAssetVerifier(() -> verifyExistingFiles);
    }

    public void setListener(DownloadListener listener) {
        this.configuredListener = listener;
    }

    public void setVerifyExistingFiles(boolean verifyExistingFiles) {
        this.verifyExistingFiles = verifyExistingFiles;
    }

    public void downloadVersion(String versionId, String versionUrl) {
        downloadVersionAsync(versionId, versionUrl, null);
    }

    public void downloadVersion(String versionId, String versionUrl, String versionSha1) {
        downloadVersionAsync(versionId, versionUrl, versionSha1);
    }

    public synchronized Future<?> downloadVersionAsync(String versionId, String versionUrl) {
        return downloadVersionAsync(versionId, versionUrl, null);
    }

    public synchronized Future<?> downloadVersionAsync(String versionId, String versionUrl,
                                                       String versionSha1) {
        cancelDownload();
        DownloadListener runListener = configuredListener;
        Future<?> task = versionDownloadExecutor.submit(() -> {
            downloadVersionInternal(versionId, versionUrl, versionSha1, runListener);
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

    private void downloadVersionInternal(String versionId, String versionUrl, String versionSha1,
                                         DownloadListener runListener) throws Exception {
        try {
            if (runListener != null) runListener.onStatus("正在下载版本信息...");

            File versionDir = FileUtil.safeVersionDirectory(ECLConfig.getVersionsDir(), versionId);
            Files.createDirectories(versionDir.toPath());
            try (FileLockLease versionLock = FileLockLease.tryAcquire(
                    ManagedLockPaths.versionDownload(
                            ECLConfig.getVersionsDir().toPath(), versionId))) {
                if (versionLock == null) {
                    throw new IOException("Version download or migration is already in progress: "
                            + versionId);
                }
                Files.deleteIfExists(versionDir.toPath().resolve(
                        ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER));

                JsonObject versionJson = readVersionMetadata(versionUrl, versionSha1);
                File versionJsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
                HttpUtil.writeJson(versionJsonFile, versionJson);
                checkCancelled();

                JsonObject downloads = versionJson.has("downloads")
                        ? versionJson.getAsJsonObject("downloads") : null;
                JsonObject client = downloads != null && downloads.has("client")
                        ? downloads.getAsJsonObject("client") : null;
                if (client != null) {
                    if (runListener != null) runListener.onStatus("正在下载游戏主文件...");
                    String clientUrl = client.get("url").getAsString();
                    String clientSha1 = InstallHelpers.requireSha1(
                            client.has("sha1") ? client.get("sha1").getAsString() : null,
                            "Minecraft client");
                    long clientSize = GameManifestParser.requiredPositiveSize(
                            client, "size", "Minecraft client");
                    File clientJar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), versionId);
                    if (assetVerifier.needsDownload(clientJar, clientSha1)) {
                        File temporaryClient = new File(clientJar.getAbsolutePath()
                                + ".ecl-download-" + UUID.randomUUID() + ".tmp");
                        try {
                            HttpUtil.downloadFileWithProgress(clientUrl, temporaryClient,
                                    new HttpUtil.ProgressCallback() {
                                        @Override
                                        public void onStart(long total) {
                                            if (runListener != null) {
                                                runListener.onProgress(0, total);
                                            }
                                        }

                                        @Override
                                        public void onProgress(long downloaded, long total) {
                                            if (runListener != null) {
                                                runListener.onProgress(downloaded, total);
                                            }
                                        }

                                        @Override
                                        public void onComplete(File file) {
                                        }
                                    }, sourceCallback("游戏主文件", runListener), clientSize);
                            if (temporaryClient.length() != clientSize) {
                                throw new IOException(
                                        "Minecraft client size does not match metadata");
                            }
                            assetVerifier.verifyDownloadedFile(temporaryClient, clientSha1);
                            atomicReplace(temporaryClient.toPath(), clientJar.toPath());
                        } finally {
                            Files.deleteIfExists(temporaryClient.toPath());
                        }
                    }
                } else if (!GameManifestParser.hasUsableInheritedClient(versionJson)) {
                    throw new IOException("版本缺少 client 下载信息，且继承版本客户端不可用: "
                            + versionId);
                }
                checkCancelled();

                if (runListener != null) runListener.onStatus("正在下载依赖库...");
                downloadLibraries(versionJson, runListener);
                checkCancelled();

                if (runListener != null) runListener.onStatus("正在下载资源文件...");
                downloadAssets(versionJson, runListener);
                checkCancelled();
                Files.writeString(versionDir.toPath().resolve(
                        ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER), "complete",
                        java.nio.charset.StandardCharsets.UTF_8);
            }

            if (runListener != null) runListener.onStatus("下载完成！");
            if (runListener != null) runListener.onComplete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (runListener != null) runListener.onStatus("下载已取消");
            throw new CancellationException("download cancelled");
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                if (runListener != null) runListener.onStatus("下载已取消");
                throw new CancellationException("download cancelled");
            }
            LOGGER.warn("Game download failed for version {}", versionId, e);
            if (runListener != null) runListener.onError(GameDownloadErrorClassifier.classify(e));
            throw e;
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("download cancelled");
    }

    private static JsonObject readVersionMetadata(String versionUrl, String versionSha1)
            throws IOException {
        URI metadataUri;
        try {
            metadataUri = URI.create(versionUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid version metadata URL: " + versionUrl, invalid);
        }
        byte[] metadataBytes = HttpUtil.getBytes(versionUrl, MAX_VERSION_METADATA_BYTES);
        String expectedSha1 = versionSha1 == null ? "" : versionSha1.trim();
        if (!expectedSha1.isBlank()) {
            if (!InstallHelpers.hasSha1(expectedSha1)
                    || !FileUtil.verifySha1(metadataBytes, expectedSha1)) {
                throw new IOException("Minecraft version metadata SHA-1 mismatch");
            }
        } else if (!NetworkUriPolicy.isLoopbackHostLiteral(metadataUri.getHost())) {
            throw new IOException("Minecraft version metadata is missing a valid SHA-1 digest");
        }
        try {
            return JsonParser.parseString(new String(metadataBytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("Minecraft version metadata is not valid JSON", invalid);
        }
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

    private void downloadLibraries(JsonObject versionJson, DownloadListener runListener) throws IOException {
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries == null) return;
        List<String> missingLibraries = GameManifestParser.getMissingLibraries(versionJson);
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
                String nativeKey = InstallHelpers.nativeClassifierKey(lib, classifiers,
                        nativePlatform.osName(), nativePlatform.archBits(),
                        nativePlatform.nativeClassifier());
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
        String url = GameManifestParser.requiredString(artifact, "url", sourceLabel + " URL");
        String path = GameManifestParser.requiredString(artifact, "path", sourceLabel + " path");
        String sha1 = InstallHelpers.requireSha1(
                artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
                sourceLabel + " " + path);
        long size = artifact.has("size") ? artifact.get("size").getAsLong() : -1L;
        File target = FileUtil.safeResolveUnder(ECLConfig.getLibrariesDir(), path);
        if (assetVerifier.needsDownload(target, sha1)) {
            tasks.add(new GameDownloadBatchExecutor.DownloadTask(
                    url, target, sha1, size, sourceLabel));
        }
    }

    private void downloadAssets(JsonObject versionJson, DownloadListener runListener) throws IOException {
        JsonElement assetIndexElement = versionJson.get("assetIndex");
        if (assetIndexElement == null || !assetIndexElement.isJsonObject()) return;
        JsonObject assetIndex = assetIndexElement.getAsJsonObject();
        String assetId = GameManifestParser.requiredString(assetIndex, "id", "asset index id");
        FileUtil.requireSafeVersionId(assetId);
        String assetUrl = GameManifestParser.requiredString(assetIndex, "url", "asset index URL");
        File assetDir = new File(ECLConfig.getAssetsDir(), "objects");
        File indexFile = FileUtil.safeResolveUnder(ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");
        String indexSha1 = InstallHelpers.requireSha1(
                assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null,
                "asset index " + assetId);
        long indexSize = GameManifestParser.requiredPositiveSize(assetIndex, "size", "asset index " + assetId);
        if (assetVerifier.needsDownload(indexFile, indexSha1)) {
            Files.createDirectories(indexFile.toPath().toAbsolutePath().getParent());
            File temporaryIndex = new File(indexFile.getAbsolutePath() + ".ecl-download-"
                    + UUID.randomUUID() + ".tmp");
            try {
                HttpUtil.downloadFileWithProgress(assetUrl, temporaryIndex, null,
                        sourceCallback("资源索引", runListener), indexSize);
                if (temporaryIndex.length() != indexSize) {
                    throw new IOException("Asset index size does not match metadata: " + assetId);
                }
                assetVerifier.verifyDownloadedFile(temporaryIndex, indexSha1);
                atomicReplace(temporaryIndex.toPath(), indexFile.toPath());
            } finally {
                Files.deleteIfExists(temporaryIndex.toPath());
            }
        }
        JsonObject objects = HttpUtil.readJson(indexFile).getAsJsonObject("objects");
        List<GameDownloadBatchExecutor.DownloadTask> tasks = new ArrayList<>();
        for (String name : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(name);
            String hash = obj.get("hash").getAsString();
            if (!hash.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("资源对象哈希无效: " + hash);
            }
            String subPath = hash.substring(0, 2) + "/" + hash;
            long size = GameManifestParser.requiredPositiveSize(obj, "size", "asset object " + name);
            File target = FileUtil.safeResolveUnder(assetDir, subPath);
            if (assetVerifier.needsDownload(target, hash)) {
                tasks.add(new GameDownloadBatchExecutor.DownloadTask(
                        "https://resources.download.minecraft.net/" + subPath,
                        target, hash, size, "资源文件"));
            }
        }
        batchExecutor.download(tasks, "资源文件", runListener);
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

    private static void atomicReplace(java.nio.file.Path source, java.nio.file.Path target)
            throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<String> getMissingLibraries(JsonObject versionJson) {
        return GameManifestParser.getMissingLibraries(versionJson);
    }

    static String nativeClassifierKey(JsonObject library, String osName, String archBits) {
        return InstallHelpers.nativeClassifierKey(library, osName, archBits);
    }

    static String nativeClassifierKey(JsonObject library, JsonObject classifiers, String osName,
                                      String archBits, String nativeClassifier) {
        return InstallHelpers.nativeClassifierKey(library, classifiers, osName, archBits,
                nativeClassifier);
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
