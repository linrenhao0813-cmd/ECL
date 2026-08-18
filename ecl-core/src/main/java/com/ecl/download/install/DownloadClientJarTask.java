package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.task.Task;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;

/**
 * Phase 2 of a version install: download and verify the client jar, honouring the inherited-client
 * shortcut (a loader profile may reuse the base version's client jar).
 */
public final class DownloadClientJarTask extends Task<Void> {

    private final String versionId;
    private final InstallState state;

    public DownloadClientJarTask(String versionId, InstallState state) {
        super("下载游戏主文件");
        this.versionId = versionId;
        this.state = state;
    }

    @Override
    protected Void execute() throws Exception {
        JsonObject versionJson = state.versionJson();
        JsonObject downloads = versionJson.has("downloads") ? versionJson.getAsJsonObject("downloads") : null;
        JsonObject client = downloads != null && downloads.has("client")
                ? downloads.getAsJsonObject("client") : null;

        if (client != null) {
            state.setStatus("正在下载游戏主文件...");
            state.setProgressActive(true);
            String clientUrl = client.get("url").getAsString();
            String clientSha1 = client.has("sha1") ? client.get("sha1").getAsString() : null;
            File clientJar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), versionId);
            if (InstallHelpers.needsDownload(clientJar, clientSha1, true)) {
                HttpUtil.downloadFileWithProgress(clientUrl, clientJar, new HttpUtil.ProgressCallback() {
                    @Override
                    public void onStart(long total) {
                        state.setProgress(0, total);
                        reportProgress(0.0);
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        state.setProgress(downloaded, total);
                        reportProgress(total <= 0 ? 0.0 : (double) downloaded / total);
                    }

                    @Override
                    public void onComplete(File file) {
                        reportProgress(1.0);
                    }
                }, InstallHelpers.sourceCallback("游戏主文件", state));
                InstallHelpers.verifyDownloadedFile(clientJar, clientSha1);
            }
            state.setProgressActive(false);
        } else if (!hasUsableInheritedClient(versionJson)) {
            throw new IOException("版本缺少 client 下载信息，且继承版本客户端不可用: " + versionId);
        }
        return null;
    }

    private boolean hasUsableInheritedClient(JsonObject versionJson) throws IOException {
        if (!versionJson.has("inheritsFrom")) {
            return false;
        }
        String current = versionJson.get("inheritsFrom").getAsString();
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            File jar = FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), current);
            if (jar.isFile()) {
                return true;
            }
            File jsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), current);
            if (!jsonFile.isFile()) {
                return false;
            }
            JsonObject parentJson = HttpUtil.readJson(jsonFile);
            if (parentJson.has("jar")) {
                String jarId = parentJson.get("jar").getAsString();
                return FileUtil.safeVersionJar(ECLConfig.getVersionsDir(), jarId).isFile();
            }
            current = parentJson.has("inheritsFrom")
                    ? parentJson.get("inheritsFrom").getAsString() : null;
        }
        return false;
    }
}
