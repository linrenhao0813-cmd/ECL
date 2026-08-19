package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.PlatformUtil;
import com.ecl.util.RuleEvaluator;
import com.ecl.util.ThreadFactories;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class GameDownloader implements DownloadService {
    public interface DownloadListener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
        void onError(String message);
        void onComplete();
    }

    private final ExecutorService versionDownloadExecutor;
    private final ExecutorService fileDownloadExecutor;
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
                () -> downloadVersionInternal(versionId, versionUrl, runListener));
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

    private void downloadVersionInternal(String versionId, String versionUrl, DownloadListener runListener) {
        try {
            if (runListener != null) runListener.onStatus("正在下载版本信息...");

            File versionDir = FileUtil.safeVersionDirectory(ECLConfig.getVersionsDir(), versionId);
            versionDir.mkdirs();

            JsonObject versionJson = HttpUtil.getJsonWithMirrors(versionUrl, sourceCallback("版本信息", runListener));
            File versionJsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
            HttpUtil.writeJson(versionJsonFile, versionJson);
            checkCancelled();

            JsonObject downloads = versionJson.has("downloads") ? versionJson.getAsJsonObject("downloads") : null;
            JsonObject client = downloads != null && downloads.has("client")
                    ? downloads.getAsJsonObject("client") : null;
            if (client != null) {
                if (runListener != null) runListener.onStatus("正在下载游戏主文件...");
                String clientUrl = client.get("url").getAsString();
                String clientSha1 = client.has("sha1") ? client.get("sha1").getAsString() : null;
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
                    }, sourceCallback("游戏主文件", runListener));
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
                return;
            }
            if (runListener != null) runListener.onError("下载失败: " + e.getMessage());
        }
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
        List<FileDownloadTask> tasks = new ArrayList<>();

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
                    artifact.addProperty("path", MavenCoordinates.repositoryPath(name));
                    artifact.addProperty("url", MavenCoordinates.repositoryUrl(repository, name));
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

        downloadConcurrently(tasks, "依赖库", runListener);
    }

    private void addDownloadIfNeeded(List<FileDownloadTask> tasks, JsonObject artifact, String sourceLabel)
            throws IOException {
        String url = artifact.get("url").getAsString();
        String path = artifact.get("path").getAsString();
        String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
        File target = FileUtil.safeResolveUnder(ECLConfig.getLibrariesDir(), path);
        if (needsDownload(target, sha1)) {
            tasks.add(new FileDownloadTask(url, target, sha1, sourceLabel));
        }
    }

    private void downloadAssets(JsonObject versionJson, DownloadListener runListener) throws IOException {
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        if (assetIndex == null) return;

        String assetId = assetIndex.get("id").getAsString();
        String assetUrl = assetIndex.get("url").getAsString();
        File assetDir = new File(ECLConfig.getAssetsDir(), "objects");
        File indexFile = FileUtil.safeResolveUnder(ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");

        String indexSha1 = assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null;
        if (needsDownload(indexFile, indexSha1)) {
            indexFile.getParentFile().mkdirs();
            HttpUtil.downloadFile(assetUrl, indexFile, sourceCallback("资源索引", runListener));
            verifyDownloadedFile(indexFile, indexSha1);
        }

        JsonObject objects = HttpUtil.readJson(indexFile).getAsJsonObject("objects");
        List<FileDownloadTask> tasks = new ArrayList<>();
        for (String name : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(name);
            String hash = obj.get("hash").getAsString();
            if (!hash.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("资源对象哈希无效: " + hash);
            }
            String subPath = hash.substring(0, 2) + "/" + hash;
            File target = FileUtil.safeResolveUnder(assetDir, subPath);
            if (needsDownload(target, hash)) {
                tasks.add(new FileDownloadTask(
                        "https://resources.download.minecraft.net/" + subPath,
                        target, hash, "资源文件"));
            }
        }

        downloadConcurrently(tasks, "资源文件", runListener);
    }

    boolean needsDownload(File target, String expectedSha1) {
        if (!target.isFile()) return true;
        return verifyExistingFiles && hasSha1(expectedSha1) && !FileUtil.verifySha1(target, expectedSha1);
    }

    private void verifyDownloadedFile(File target, String expectedSha1) throws IOException {
        if (!hasSha1(expectedSha1) || FileUtil.verifySha1(target, expectedSha1)) return;
        if (!target.delete()) target.deleteOnExit();
        throw new IOException(target.getName() + " 的 SHA-1 校验失败");
    }

    private static boolean hasSha1(String sha1) {
        return sha1 != null && !sha1.isBlank();
    }

    static String nativeClassifierKey(JsonObject library, String osName, String archBits) {
        if (library.has("natives")) {
            JsonObject natives = library.getAsJsonObject("natives");
            if (natives.has(osName)) {
                return natives.get(osName).getAsString().replace("${arch}", archBits);
            }
        }
        return "natives-" + osName;
    }

    static String nativeClassifierKey(JsonObject library, JsonObject classifiers, String osName,
                                      String archBits, String nativeClassifier) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        boolean arm = nativeClassifier != null && nativeClassifier.endsWith("-arm64");
        if (arm) {
            candidates.addAll(java.util.Arrays.asList(
                    com.ecl.util.MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        }
        candidates.add(nativeClassifierKey(library, osName, archBits));
        candidates.addAll(java.util.Arrays.asList(
                com.ecl.util.MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        return candidates.stream().filter(classifiers::has).findFirst().orElse(null);
    }

    private void downloadConcurrently(List<FileDownloadTask> tasks, String phase,
                                      DownloadListener runListener) throws IOException {
        if (tasks.isEmpty()) {
            if (runListener != null) runListener.onStatus(phase + "已是最新，无需下载");
            return;
        }

        int threadCount = Math.min(ECLConfig.DOWNLOAD_THREADS, tasks.size());
        if (runListener != null) {
            runListener.onStatus("使用 " + threadCount + " 个线程下载" + phase + "，共 " + tasks.size() + " 个文件...");
        }

        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(fileDownloadExecutor);
        List<Future<Void>> phaseTasks = new ArrayList<>(tasks.size());

        for (FileDownloadTask task : tasks) {
            phaseTasks.add(completionService.submit(() -> {
                downloadAndVerify(task, runListener);
                return null;
            }));
        }

        IOException firstError = null;
        int completed = 0;
        try {
            while (completed < tasks.size()) {
                try {
                    completionService.take().get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    IOException error = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException(cause == null ? e : cause);
                    if (firstError == null) firstError = error;
                }
                completed++;
                if (runListener != null && (completed == tasks.size() || completed % 25 == 0)) {
                    runListener.onStatus("下载" + phase + ": " + completed + "/" + tasks.size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(phase + "下载被中断", e);
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                phaseTasks.forEach(task -> task.cancel(true));
            }
        }

        if (firstError != null) {
            throw new IOException(phase + "下载失败: " + firstError.getMessage(), firstError);
        }
    }

    private void downloadAndVerify(FileDownloadTask task, DownloadListener runListener) throws IOException {
        HttpUtil.downloadFile(task.url, task.target, sourceCallback(task.sourceLabel, runListener));
        verifyDownloadedFile(task.target, task.sha1);
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
            String osName = PlatformUtil.current().minecraftName();
            return new NativePlatform(osName, bits,
                    osName + "-" + FileUtil.nativeArchitecture(architecture));
        }
    }

    private static final class FileDownloadTask {
        private final String url;
        private final File target;
        private final String sha1;
        private final String sourceLabel;

        private FileDownloadTask(String url, File target, String sha1, String sourceLabel) {
            this.url = url;
            this.target = target;
            this.sha1 = sha1;
            this.sourceLabel = sourceLabel;
        }
    }
}
