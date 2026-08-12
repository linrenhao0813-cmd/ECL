package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.task.Task;
import com.ecl.util.PlatformUtil;
import com.ecl.util.RuleEvaluator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 of a version install: download every missing library and native classifier for the
 * current platform, concurrently, verifying SHA-1 after each download.
 */
public final class DownloadLibrariesTask extends Task<Void> {

    private final InstallState state;
    private final java.util.concurrent.ExecutorService fileExecutor;
    private final boolean verifyExistingFiles;

    public DownloadLibrariesTask(InstallState state,
                                 java.util.concurrent.ExecutorService fileExecutor,
                                 boolean verifyExistingFiles) {
        super("下载依赖库");
        this.state = state;
        this.fileExecutor = fileExecutor;
        this.verifyExistingFiles = verifyExistingFiles;
    }

    @Override
    protected Void execute() throws Exception {
        JsonObject versionJson = state.versionJson();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries == null) {
            return null;
        }

        List<String> missing = missingLibraryNames(libraries);
        if (!missing.isEmpty()) {
            state.setStatus("检测到 " + missing.size() + " 个缺失的依赖库");
        }

        PlatformBits platform = PlatformBits.current();
        List<InstallHelpers.FileDownload> tasks = new ArrayList<>();
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            if (library.has("rules") && !RuleEvaluator.isAllowed(library.getAsJsonArray("rules"))) {
                continue;
            }
            JsonObject downloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;
            if (downloads == null) {
                continue;
            }
            if (downloads.has("artifact")) {
                addIfNeeded(tasks, downloads.getAsJsonObject("artifact"), "依赖库");
            }
            if (downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                String nativeKey = InstallHelpers.nativeClassifierKey(library, platform.osName, platform.archBits);
                if (nativeKey != null && classifiers.has(nativeKey)) {
                    addIfNeeded(tasks, classifiers.getAsJsonObject(nativeKey), "原生库");
                }
            }
        }

        InstallHelpers.downloadConcurrently(tasks, "依赖库", fileExecutor, state,
                (done, total) -> reportProgress(total <= 0 ? 0.0 : (double) done / total));
        return null;
    }

    private void addIfNeeded(List<InstallHelpers.FileDownload> tasks, JsonObject artifact, String label) {
        String url = artifact.get("url").getAsString();
        String path = artifact.get("path").getAsString();
        String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
        File target = new File(ECLConfig.getLibrariesDir(), path);
        if (InstallHelpers.needsDownload(target, sha1, verifyExistingFiles)) {
            tasks.add(new InstallHelpers.FileDownload(url, target, sha1, label));
        }
    }

    private List<String> missingLibraryNames(JsonArray libraries) {
        List<String> missing = new ArrayList<>();
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            if (library.has("rules") && !RuleEvaluator.isAllowed(library.getAsJsonArray("rules"))) {
                continue;
            }
            JsonObject downloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;
            if (downloads != null && downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                File target = new File(ECLConfig.getLibrariesDir(), path);
                if (!target.exists()) {
                    missing.add(library.has("name") ? library.get("name").getAsString() : path);
                }
            }
        }
        return missing;
    }

    private record PlatformBits(String osName, String archBits) {
        private static PlatformBits current() {
            String architecture = System.getProperty("os.arch", "").toLowerCase();
            String bits = architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
            return new PlatformBits(PlatformUtil.current().minecraftName(), bits);
        }
    }
}