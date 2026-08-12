package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.task.Task;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 of a version install: download the asset index and every missing indexed object
 * (resource file), verifying each with the hash from the index.
 */
public final class DownloadAssetsTask extends Task<Void> {

    private static final String RESOURCES_HOST = "https://resources.download.minecraft.net/";

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

        String assetId = assetIndex.get("id").getAsString();
        String assetUrl = assetIndex.get("url").getAsString();
        File assetDir = new File(ECLConfig.getAssetsDir(), "objects");
        File indexFile = new File(ECLConfig.getAssetsDir(), "indexes/" + assetId + ".json");
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
            String hash = object.get("hash").getAsString();
            String subPath = hash.substring(0, 2) + "/" + hash;
            File target = new File(assetDir, subPath);
            if (InstallHelpers.needsDownload(target, hash, verifyExistingFiles)) {
                tasks.add(new InstallHelpers.FileDownload(RESOURCES_HOST + subPath, target, hash, "资源文件"));
            }
        }

        InstallHelpers.downloadConcurrently(tasks, "资源文件", fileExecutor, state,
                (done, total) -> reportProgress(total <= 0 ? 0.0 : (double) done / total));
        return null;
    }
}