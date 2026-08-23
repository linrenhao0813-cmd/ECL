package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.task.Task;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 4 of a version install: download the asset index and every missing indexed object
 * (resource file), verifying each with the hash from the index.
 */
public final class DownloadAssetsTask extends Task<Void> {

    private static final String RESOURCES_HOST = "https://resources.download.minecraft.net/";
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-fA-F]{40}");

    private final InstallState state;
    private final java.util.concurrent.ExecutorService fileExecutor;
    private final boolean verifyExistingFiles;

    public DownloadAssetsTask(InstallState state,
                              java.util.concurrent.ExecutorService fileExecutor,
                              boolean verifyExistingFiles) {
        super("下载资源文件");
        this.state = state;
        this.fileExecutor = fileExecutor;
        this.verifyExistingFiles = verifyExistingFiles;
    }

    @Override
    protected Void execute() throws Exception {
        JsonObject versionJson = state.versionJson();
        JsonObject assetIndex = versionJson.has("assetIndex") ? versionJson.getAsJsonObject("assetIndex") : null;
        if (assetIndex == null) {
            return null;
        }

        String assetId = requireSafeAssetId(JsonUtil.getString(assetIndex, "id", ""));
        String assetUrl = JsonUtil.getString(assetIndex, "url", "");
        if (assetUrl.isBlank()) {
            throw new IOException("资源索引缺少下载地址: " + assetId);
        }
        File assetDir = new File(ECLConfig.getAssetsDir(), "objects");
        File indexFile = FileUtil.safeResolveUnder(
                ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");
        String indexSha1 = assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null;
        if (InstallHelpers.needsDownload(indexFile, indexSha1, verifyExistingFiles)) {
            indexFile.getParentFile().mkdirs();
            HttpUtil.downloadFile(assetUrl, indexFile, InstallHelpers.sourceCallback("资源索引", state));
            InstallHelpers.verifyDownloadedFile(indexFile, indexSha1);
        }

        JsonObject objects = HttpUtil.readJson(indexFile).getAsJsonObject("objects");
        List<InstallHelpers.FileDownload> tasks = new ArrayList<>();
        for (String name : objects.keySet()) {
            JsonObject object = objects.getAsJsonObject(name);
            String hash = requireSha1(JsonUtil.getString(object, "hash", ""), name);
            String subPath = hash.substring(0, 2) + "/" + hash;
            File target = FileUtil.safeResolveUnder(assetDir, subPath);
            if (InstallHelpers.needsDownload(target, hash, verifyExistingFiles)) {
                tasks.add(new InstallHelpers.FileDownload(RESOURCES_HOST + subPath, target, hash, "资源文件"));
            }
        }

        InstallHelpers.downloadConcurrently(tasks, "资源文件", fileExecutor, state,
                (done, total) -> reportProgress(total <= 0 ? 0.0 : (double) done / total));
        return null;
    }

    static String requireSafeAssetId(String assetId) throws IOException {
        FileUtil.requireSafeVersionId(assetId);
        return assetId;
    }

    static String requireSha1(String hash, String assetName) throws IOException {
        if (hash == null || !SHA1_PATTERN.matcher(hash).matches()) {
            throw new IOException("资源索引包含无效 SHA-1: " + assetName);
        }
        return hash;
    }
}
