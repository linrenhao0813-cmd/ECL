package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.task.Task;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.File;

/**
 * Phase 1 of a version install: fetch the version JSON (through official/mirror sources), persist it
 * next to the other version files, and expose it to later phases via {@link InstallState}.
 */
public final class FetchVersionMetadataTask extends Task<JsonObject> {

    private final String versionId;
    private final String versionUrl;
    private final InstallState state;

    public FetchVersionMetadataTask(String versionId, String versionUrl, InstallState state) {
        super("下载版本信息");
        this.versionId = versionId;
        this.versionUrl = versionUrl;
        this.state = state;
    }

    @Override
    protected JsonObject execute() throws Exception {
        state.setStatus("正在下载版本信息...");
        File versionDir = FileUtil.safeVersionDirectory(ECLConfig.getVersionsDir(), versionId);
        versionDir.mkdirs();

        JsonObject versionJson = HttpUtil.getJsonWithMirrors(
                versionUrl, InstallHelpers.sourceCallback("版本信息", state));
        File versionJsonFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), versionId);
        HttpUtil.writeJson(versionJsonFile, versionJson);
        InstallHelpers.checkCancelled();
        state.setVersionJson(versionJson);
        reportProgress(1.0);
        return versionJson;
    }
}
