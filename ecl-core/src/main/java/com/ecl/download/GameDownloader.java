package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.ClientLoggingConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.game.VersionRepository;
import com.ecl.util.FileLockLease;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.ManagedLockPaths;
import com.ecl.util.NetworkUriPolicy;
import com.ecl.util.JsonUtil;
import com.ecl.util.Messages;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_VERSION_METADATA_BYTES = 4 * 1024 * 1024;
    public interface DownloadListener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
        void onError(String message);
        void onComplete();
    }

    private final File versionsDirectory;
    private final File librariesDirectory;
    private final File assetsDirectory;
    private final ExecutorService versionDownloadExecutor;
    private final ExecutorService fileDownloadExecutor;
    private final GameDownloadBatchExecutor batchExecutor;
    private final AtomicReference<Future<?>> activeDownload = new AtomicReference<>();
    private volatile DownloadListener configuredListener;
    private volatile boolean verifyExistingFiles = true;

    public GameDownloader() {
        this(ECLConfig.MAX_DOWNLOAD_CONCURRENT);
    }

    public GameDownloader(int downloadThreads) {
        this(downloadThreads, ECLConfig.getVersionsDir(), ECLConfig.getLibrariesDir(), ECLConfig.getAssetsDir());
    }

    public GameDownloader(int downloadThreads, File versionsDirectory, File librariesDirectory, File assetsDirectory) {
        this.versionsDirectory = versionsDirectory;
        this.librariesDirectory = librariesDirectory;
        this.assetsDirectory = assetsDirectory;
        int workerCount = ECLConfig.clampDownloadConcurrency(downloadThreads);
        versionDownloadExecutor = Executors.newSingleThreadExecutor(
                ThreadFactories.daemon("ecl-version-download"));
        fileDownloadExecutor = Executors.newFixedThreadPool(
                workerCount,
                ThreadFactories.daemon("ecl-file-download"));
        batchExecutor = new GameDownloadBatchExecutor(fileDownloadExecutor, workerCount);
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

            File versionDir = FileUtil.safeVersionDirectory(versionsDirectory, versionId);
            Files.createDirectories(versionDir.toPath());
            try (FileLockLease versionLock = FileLockLease.tryAcquire(
                    ManagedLockPaths.versionDownload(
                            versionsDirectory.toPath(), versionId))) {
                if (versionLock == null) {
                    throw new IOException("Version download or migration is already in progress: "
                            + versionId);
                }
                Files.deleteIfExists(versionDir.toPath().resolve(
                        ECLConfig.VERSION_DOWNLOAD_COMPLETE_MARKER));

                JsonObject versionJson = readVersionMetadata(versionUrl, versionSha1);
                File versionJsonFile = FileUtil.safeVersionJson(versionsDirectory, versionId);
                HttpUtil.writeJson(versionJsonFile, versionJson);
                checkCancelled();

                downloadClient(versionJson, versionId, runListener);
                checkCancelled();

                if (runListener != null) runListener.onStatus("正在下载依赖库...");
                downloadLibraries(versionJson, runListener);
                checkCancelled();

                if (runListener != null) runListener.onStatus("正在下载资源文件...");
                downloadAssets(versionJson, runListener);
                checkCancelled();
                ClientLoggingConfig logging = new VersionRepository(versionsDirectory)
                        .resolve(versionId).clientLogging();
                if (logging != null) logging.ensureDownloaded(assetsDirectory);
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

    private void downloadClient(JsonObject versionJson, String versionId,
                                DownloadListener runListener) throws IOException {
        JsonObject downloads = versionJson.has("downloads")
                ? versionJson.getAsJsonObject("downloads") : null;
        JsonObject client = downloads != null && downloads.has("client")
                ? downloads.getAsJsonObject("client") : null;
        if (client == null) {
            if (!GameManifestParser.hasUsableInheritedClient(versionJson, versionsDirectory)) {
                throw new IOException("版本缺少 client 下载信息，且继承版本客户端不可用: "
                        + versionId);
            }
            return;
        }

        if (runListener != null) runListener.onStatus("正在下载游戏主文件...");
        String clientUrl = client.get("url").getAsString();
        String clientSha1 = InstallHelpers.requireSha1(
                client.has("sha1") ? client.get("sha1").getAsString() : null,
                "Minecraft client");
        long clientSize = GameManifestParser.requiredPositiveSize(
                client, "size", "Minecraft client");
        File clientJar = FileUtil.safeVersionJar(versionsDirectory, versionId);
        if (!InstallHelpers.needsDownload(clientJar, clientSha1, verifyExistingFiles)) return;

        batchExecutor.downloadSingleVerified(new GameDownloadBatchExecutor.DownloadTask(
                clientUrl, clientJar, clientSha1, clientSize, "游戏主文件",
                versionsDirectory.toPath()), runListener, new HttpUtil.ProgressCallback() {
                    @Override public void onStart(long total) {
                        if (runListener != null) runListener.onProgress(0, total);
                    }

                    @Override public void onProgress(long downloaded, long total) {
                        if (runListener != null) runListener.onProgress(downloaded, total);
                    }

                    @Override public void onComplete(File file) { }
                });
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
        List<String> missingLibraries = GameManifestParser.getMissingLibraries(versionJson, librariesDirectory);
        if (!missingLibraries.isEmpty() && runListener != null) {
            runListener.onStatus("检测到 " + missingLibraries.size() + " 个缺失的依赖库");
        }
        NativePlatform nativePlatform = NativePlatform.current();
        List<GameDownloadBatchExecutor.DownloadTask> tasks = new ArrayList<>();

        for (JsonElement libraryElement : libraries) {
            JsonObject library = libraryElement.getAsJsonObject();
            if (library.has("rules") && !RuleEvaluator.isAllowed(library.getAsJsonArray("rules"))) {
                continue;
            }
            JsonObject downloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;
            addLibraryArtifact(tasks, library, downloads);
            addNativeArtifact(tasks, library, downloads, nativePlatform);
        }
        batchExecutor.download(tasks, "依赖库", runListener);
    }

    private void addLibraryArtifact(List<GameDownloadBatchExecutor.DownloadTask> tasks,
                                    JsonObject library, JsonObject downloads) throws IOException {
        if (downloads != null && (downloads.has("artifact") || !downloads.has("classifiers"))) {
            if (downloads.has("artifact")) {
                addDownloadIfNeeded(tasks, downloads.getAsJsonObject("artifact"), "依赖库");
            }
            return;
        }

        String name = library.has("name") ? library.get("name").getAsString() : "";
        String repository = library.has("url") ? library.get("url").getAsString() : "";
        if (!MavenCoordinates.isSimpleCoordinate(name) || repository.isBlank()) return;

        JsonObject artifact = new JsonObject();
        String resolvedUrl = MavenCoordinates.repositoryUrl(repository, name);
        artifact.addProperty("path", MavenCoordinates.repositoryPath(name));
        artifact.addProperty("url", resolvedUrl);
        artifact.addProperty("sha1", InstallHelpers.resolveRemoteSha1(
                resolvedUrl, "Maven library " + name));
        addDownloadIfNeeded(tasks, artifact, "依赖库");
    }

    private void addNativeArtifact(List<GameDownloadBatchExecutor.DownloadTask> tasks,
                                   JsonObject library, JsonObject downloads,
                                   NativePlatform nativePlatform) throws IOException {
        if (downloads == null || !downloads.has("classifiers")) return;

        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
        String nativeKey = InstallHelpers.nativeClassifierKey(library, classifiers,
                nativePlatform.osName(), nativePlatform.archBits(),
                nativePlatform.nativeClassifier());
        if (nativeKey != null) {
            addDownloadIfNeeded(tasks, classifiers.getAsJsonObject(nativeKey), "原生库");
        }
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
        File target = FileUtil.safeResolveUnder(librariesDirectory, path);
        if (InstallHelpers.needsDownload(target, sha1, verifyExistingFiles)) {
            tasks.add(new GameDownloadBatchExecutor.DownloadTask(
                    url, target, sha1, size, sourceLabel,
                    librariesDirectory.toPath()));
        }
    }

    private void downloadAssets(JsonObject versionJson, DownloadListener runListener) throws IOException {
        JsonElement assetIndexElement = versionJson.get("assetIndex");
        if (assetIndexElement == null || !assetIndexElement.isJsonObject()) return;
        JsonObject assetIndex = assetIndexElement.getAsJsonObject();
        String assetId = GameManifestParser.requiredString(assetIndex, "id", "asset index id");
        FileUtil.requireSafeVersionId(assetId);
        String assetUrl = GameManifestParser.requiredString(assetIndex, "url", "asset index URL");
        File assetDir = new File(assetsDirectory, "objects");
        File indexFile = FileUtil.safeResolveUnder(assetsDirectory, "indexes/" + assetId + ".json");
        String indexSha1 = InstallHelpers.requireSha1(
                assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null,
                "asset index " + assetId);
        long indexSize = GameManifestParser.requiredPositiveSize(assetIndex, "size", "asset index " + assetId);
        if (InstallHelpers.needsDownload(indexFile, indexSha1, verifyExistingFiles)) {
            batchExecutor.downloadSingleVerified(new GameDownloadBatchExecutor.DownloadTask(
                    assetUrl, indexFile, indexSha1, indexSize, "资源索引",
                    assetsDirectory.toPath()), runListener, null);
        }
        JsonObject indexJson = HttpUtil.readJson(indexFile);
        JsonObject objects = indexJson.getAsJsonObject("objects");
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
            if (InstallHelpers.needsDownload(target, hash, verifyExistingFiles)) {
                tasks.add(new GameDownloadBatchExecutor.DownloadTask(
                        "https://resources.download.minecraft.net/" + subPath,
                        target, hash, size, "资源文件", assetDir.toPath()));
            }
        }
        batchExecutor.download(tasks, "资源文件", runListener);
        materializeLegacyAssets(indexJson, objects, assetId, runListener);
    }

    /**
     * Old indexes ({@code virtual}, {@code map_to_resources}) expect the payloads under their asset
     * name; the objects layout alone leaves 1.7.2-style versions without sounds.
     */
    private void materializeLegacyAssets(JsonObject indexJson, JsonObject objects, String assetId,
                                         DownloadListener runListener) throws IOException {
        boolean virtual = LegacyAssetMaterializer.isVirtual(indexJson);
        if (!virtual) return;
        Path objectsRoot = new File(assetsDirectory, "objects").toPath();
        if (runListener != null) runListener.onStatus("正在生成旧版虚拟资源...");
        Path virtualRoot = FileUtil.safeResolveUnder(assetsDirectory,
                "virtual/" + assetId).toPath();
        FileUtil.validateExistingAncestors(assetsDirectory.toPath(), virtualRoot);
        LegacyAssetMaterializer.materialize(objects, objectsRoot, virtualRoot);
    }

    public List<String> getMissingLibraries(JsonObject versionJson) {
        return GameManifestParser.getMissingLibraries(versionJson, librariesDirectory);
    }

    /** Download dependencies declared by a locally installed profile, including inherited libraries. */
    public void downloadLibrariesForVersion(String versionId, DownloadListener listener) throws IOException {
        JsonObject versionJson = new com.ecl.game.VersionRepository(versionsDirectory)
                .resolveRaw(versionId);
        if (listener != null) listener.onStatus("正在准备加载器依赖库...");
        downloadLibraries(versionJson, listener);
    }

    /** Repairs authenticated metadata without running the full installation pipeline. */
    void repairMetadata(String versionId, String url, String sha1, Path versions) throws IOException {
        Path file = FileUtil.safeVersionJson(versions.toFile(), versionId).toPath();
        FileUtil.validateExistingAncestors(versions, file);
        try (FileLockLease lease = FileLockLease.tryAcquire(ManagedLockPaths.versionDownload(versions, versionId))) {
            if (lease == null) throw new IOException("Version download is already in progress: " + versionId);
            JsonObject metadata = readVersionMetadata(url, sha1);
            FileUtil.validateExistingAncestors(versions, file);
            HttpUtil.writeJson(file.toFile(), metadata);
        }
    }

    /** Downloads only declarations that the read-only verifier identified as repairable. */
    void repairVerifiedFiles(String profileId, Path versions, Path libraries, Path assets, Path instancesRoot,
                             DownloadListener listener) throws IOException {
        GameInstallationVerifier verifier = new GameInstallationVerifier(versions, libraries, assets, instancesRoot);
        GameInstallationVerifier.Report report = verifier.inspect(profileId);
        JsonObject effective = new VersionRepository(versions.toFile()).resolveRaw(profileId);
        JsonObject client = object(object(effective, "downloads"), "client");
        String jarId = JsonUtil.getString(effective, "jar", profileId);
        if (jarId.isBlank()) jarId = profileId;
        repairArtifact(client, FileUtil.safeVersionJar(versions.toFile(), jarId).toPath(), versions, report, listener);
        JsonArray declaredLibraries = effective.has("libraries") ? effective.getAsJsonArray("libraries") : new JsonArray();
        for (JsonElement entry : declaredLibraries) {
            if (!entry.isJsonObject()) continue;
            JsonObject downloads = object(entry.getAsJsonObject(), "downloads");
            repairLibraryArtifact(object(downloads, "artifact"), libraries, report, listener);
            JsonObject classifiers = object(downloads, "classifiers");
            if (classifiers != null) {
                for (String classifier : classifiers.keySet()) {
                    repairLibraryArtifact(object(classifiers, classifier), libraries, report, listener);
                }
            }
        }
        JsonObject index = object(effective, "assetIndex");
        if (index != null) {
            String indexId = JsonUtil.getString(index, "id", "");
            FileUtil.requireSafeVersionId(indexId);
            Path indexFile = FileUtil.safeResolveUnder(assets.toFile(), "indexes/" + indexId + ".json").toPath();
            repairArtifact(index, indexFile, assets, report, listener);
            report = verifier.inspect(profileId);
            if (report.checks().stream().anyMatch(check -> check.category() == GameInstallationVerifier.Category.ASSET_INDEX
                    && check.status() == GameInstallationVerifier.Status.VERIFIED)) {
                JsonObject objects = object(HttpUtil.readJson(indexFile.toFile()), "objects");
                if (objects != null) {
                    for (String name : objects.keySet()) {
                        JsonObject asset = object(objects, name);
                        if (asset == null) continue;
                        String hash = JsonUtil.getString(asset, "hash", "");
                        if (!InstallHelpers.hasSha1(hash)) continue;
                        String path = hash.substring(0, 2) + "/" + hash;
                        JsonObject declaration = asset.deepCopy();
                        declaration.addProperty("sha1", hash);
                        declaration.addProperty("url", "https://resources.download.minecraft.net/" + path);
                        repairArtifact(declaration, FileUtil.safeResolveUnder(assets.toFile(), "objects/" + path).toPath(),
                                assets, report, listener);
                    }
                }
            }
        }
        JsonObject logging = object(object(effective, "logging"), "client");
        ClientLoggingConfig config;
        try {
            config = ClientLoggingConfig.parse(logging);
        } catch (IOException | RuntimeException invalidLogging) {
            return;
        }
        if (config != null && needsRepair(config.targetFile(assets.toFile()).toPath(), report)) {
            config.ensureDownloaded(assets.toFile());
        }
    }

    private void repairLibraryArtifact(JsonObject artifact, Path libraries,
                                       GameInstallationVerifier.Report report, DownloadListener listener) throws IOException {
        if (artifact == null) return;
        String path = JsonUtil.getString(artifact, "path", "");
        if (path.isBlank()) return;
        repairArtifact(artifact, FileUtil.safeResolveUnder(libraries.toFile(), path).toPath(), libraries, report, listener);
    }

    private void repairArtifact(JsonObject artifact, Path file, Path root,
                                 GameInstallationVerifier.Report report, DownloadListener listener) throws IOException {
        if (artifact == null || !needsRepair(file, report)) return;
        batchExecutor.downloadSingleVerified(new GameDownloadBatchExecutor.DownloadTask(
                JsonUtil.getString(artifact, "url", ""), file.toFile(), JsonUtil.getString(artifact, "sha1", ""),
                JsonUtil.getLong(artifact, "size", -1), Messages.get("instanceRepair.repair"), root), listener, null);
    }

    private static boolean needsRepair(Path file, GameInstallationVerifier.Report report) {
        return report.issues().stream().anyMatch(check -> check.repairable()
                && check.path().equals(file.toAbsolutePath().normalize().toString()));
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
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
            String osName = com.ecl.util.OperatingSystem.minecraftName();
            return new NativePlatform(osName, bits,
                    osName + "-" + FileUtil.nativeArchitecture(architecture));
        }
    }
}
