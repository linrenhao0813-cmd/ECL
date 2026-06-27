package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GameDownloader {
    public interface DownloadListener {
        void onStatus(String message);
        void onProgress(long downloaded, long total);
        void onError(String message);
        void onComplete();
    }

    private DownloadListener listener;

    public void setListener(DownloadListener listener) {
        this.listener = listener;
    }

    public void downloadVersion(String versionId, String versionUrl) {
        new Thread(() -> {
            try {
                if (listener != null) listener.onStatus("正在下载版本信息...");

                File versionDir = new File(ECLConfig.getVersionsDir(), versionId);
                versionDir.mkdirs();

                JsonObject versionJson = HttpUtil.getJsonWithMirrors(versionUrl, sourceCallback("版本信息"));
                File versionJsonFile = new File(versionDir, versionId + ".json");
                HttpUtil.writeJson(versionJsonFile, versionJson);

                if (listener != null) listener.onStatus("正在下载游戏主文件...");
                String clientUrl = versionJson.getAsJsonObject("downloads")
                        .getAsJsonObject("client").get("url").getAsString();
                String clientSha1 = versionJson.getAsJsonObject("downloads")
                        .getAsJsonObject("client").get("sha1").getAsString();

                File clientJar = new File(versionDir, versionId + ".jar");
                HttpUtil.downloadFileWithProgress(clientUrl, clientJar, new HttpUtil.ProgressCallback() {
                    @Override
                    public void onStart(long total) {
                        if (listener != null) listener.onProgress(0, total);
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        if (listener != null) listener.onProgress(downloaded, total);
                    }

                    @Override
                    public void onComplete(File file) {}
                }, sourceCallback("游戏主文件"));

                if (!FileUtil.verifySha1(clientJar, clientSha1)) {
                    if (listener != null) listener.onError("游戏主文件校验失败");
                    return;
                }

                if (listener != null) listener.onStatus("正在下载依赖库...");
                downloadLibraries(versionJson);

                if (listener != null) listener.onStatus("正在下载资源文件...");
                downloadAssets(versionJson);

                if (listener != null) listener.onStatus("下载完成！");
                if (listener != null) listener.onComplete();
            } catch (Exception e) {
                if (listener != null) listener.onError("下载失败: " + e.getMessage());
            }
        }).start();
    }

    private void downloadLibraries(JsonObject versionJson) throws IOException {
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        String nativeClassifier = FileUtil.getNativeClassifier();

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();

            if (lib.has("rules")) {
                if (!checkRules(lib.getAsJsonArray("rules"))) continue;
            }

            JsonObject artifacts = null;
            if (lib.has("downloads")) {
                artifacts = lib.getAsJsonObject("downloads");
            }

            if (artifacts != null && artifacts.has("artifact")) {
                JsonObject artifact = artifacts.getAsJsonObject("artifact");
                String url = artifact.get("url").getAsString();
                String path = artifact.get("path").getAsString();
                File target = new File(ECLConfig.getLibrariesDir(), path);
                String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;

                if (!target.exists() || (sha1 != null && !FileUtil.verifySha1(target, sha1))) {
                    if (listener != null) listener.onStatus("下载库: " + path);
                    HttpUtil.downloadFile(url, target, sourceCallback("依赖库"));
                }
            }

            if (artifacts != null && artifacts.has("classifiers")) {
                JsonObject classifiers = artifacts.getAsJsonObject("classifiers");
                String nativeKey = "natives-" + nativeClassifier.split("-")[0];
                if (classifiers.has(nativeKey)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                    String url = nativeArtifact.get("url").getAsString();
                    String path = nativeArtifact.get("path").getAsString();
                    File target = new File(ECLConfig.getLibrariesDir(), path);
                    if (!target.exists()) {
                        if (listener != null) listener.onStatus("下载原生库: " + path);
                        HttpUtil.downloadFile(url, target, sourceCallback("原生库"));
                    }
                }
            }
        }
    }

    private void downloadAssets(JsonObject versionJson) throws IOException {
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        if (assetIndex == null) return;

        String assetId = assetIndex.get("id").getAsString();
        String assetUrl = assetIndex.get("url").getAsString();

        File assetDir = new File(ECLConfig.getAssetsDir(), "objects");
        File indexFile = new File(ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");

        if (!indexFile.exists()) {
            indexFile.getParentFile().mkdirs();
            HttpUtil.downloadFile(assetUrl, indexFile, sourceCallback("资源索引"));
        }

        JsonObject indexJson = HttpUtil.readJson(indexFile);
        JsonObject objects = indexJson.getAsJsonObject("objects");

        int total = objects.size();
        int count = 0;

        for (String name : objects.keySet()) {
            JsonObject obj = objects.get(name).getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            String subPath = hash.substring(0, 2) + "/" + hash;
            File target = new File(assetDir, subPath);

            if (!target.exists()) {
                String url = "https://resources.download.minecraft.net/" + subPath;
                target.getParentFile().mkdirs();
                HttpUtil.downloadFile(url, target, sourceCallback("资源文件"));
            }

            count++;
            if (count % 50 == 0 && listener != null) {
                listener.onStatus("下载资源文件: " + count + "/" + total);
            }
        }
    }

    private boolean checkRules(JsonArray rules) {
        boolean allowed = rules.size() == 0;
        String osName = System.getProperty("os.name").toLowerCase();

        for (JsonElement ruleEl : rules) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean osMatch = true;

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                String name = os.has("name") ? os.get("name").getAsString() : "";
                if (name.equals("windows") && !osName.contains("win")) osMatch = false;
                if (name.equals("osx") && !osName.contains("mac")) osMatch = false;
                if (name.equals("linux") && !osName.contains("linux")) osMatch = false;
            }

            if ("allow".equals(action) && osMatch) allowed = true;
            if ("disallow".equals(action) && osMatch) allowed = false;
        }
        return allowed;
    }

    private HttpUtil.SourceCallback sourceCallback(String label) {
        return new HttpUtil.SourceCallback() {
            @Override
            public void onSource(String originalUrl, String candidateUrl, boolean mirror, String sourceName) {
                if (listener != null && mirror) {
                    listener.onStatus(label + "官方源响应较慢，切换到" + sourceName + "...");
                }
            }

            @Override
            public void onFailure(String candidateUrl, IOException error) {
                if (listener != null) {
                    listener.onStatus(label + "下载源失败，尝试下一个源: " + error.getMessage());
                }
            }
        };
    }

    public List<String> getMissingLibraries(JsonObject versionJson) {
        List<String> missing = new ArrayList<>();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    String path = artifact.get("path").getAsString();
                    File target = new File(ECLConfig.getLibrariesDir(), path);
                    if (!target.exists()) {
                        missing.add(lib.get("name").getAsString());
                    }
                }
            }
        }
        return missing;
    }
}
