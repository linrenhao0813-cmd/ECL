package com.ecl.download.install;

import com.ecl.ECLConfig;
import com.ecl.game.MavenCoordinates;
import com.ecl.task.Task;
import com.ecl.util.FileUtil;
import com.ecl.util.PlatformUtil;
import com.ecl.util.RuleEvaluator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
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
                // Fabric/Quilt style: bare Maven coordinate with a repository URL.
                String name = library.has("name") ? library.get("name").getAsString() : "";
                String repository = library.has("url") ? library.get("url").getAsString() : "";
                if (MavenCoordinates.isSimpleCoordinate(name) && !repository.isBlank()) {
                    JsonObject artifact = new JsonObject();
                    artifact.addProperty("path", MavenCoordinates.repositoryPath(name));
                    artifact.addProperty("url", MavenCoordinates.repositoryUrl(repository, name));
                    addIfNeeded(tasks, artifact, "依赖库");
                }
                continue;
            }
            if (downloads.has("artifact")) {
                addIfNeeded(tasks, downloads.getAsJsonObject("artifact"), "依赖库");
            }
            if (downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                String nativeKey = InstallHelpers.nativeClassifierKey(library, classifiers,
                        platform.osName, platform.archBits, platform.nativeClassifier);
                if (nativeKey != null) {
                    addIfNeeded(tasks, classifiers.getAsJsonObject(nativeKey), "原生库");
                }
            }
        }

        InstallHelpers.downloadConcurrently(tasks, "依赖库", fileExecutor, state,
                (done, total) -> reportProgress(total <= 0 ? 0.0 : (double) done / total));
        return null;
    }

    private void addIfNeeded(List<InstallHelpers.FileDownload> tasks, JsonObject artifact, String label)
            throws IOException {
        String url = artifact.get("url").getAsString();
        String path = artifact.get("path").getAsString();
        String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
        File target = FileUtil.safeResolveUnder(ECLConfig.getLibrariesDir(), path);
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
                File target = safeLibraryTarget(path);
                if (target != null && !target.exists()) {
                    missing.add(library.has("name") ? library.get("name").getAsString() : path);
                }
            } else if (downloads == null) {
                String name = library.has("name") ? library.get("name").getAsString() : "";
                String repository = library.has("url") ? library.get("url").getAsString() : "";
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

    private record PlatformBits(String osName, String archBits, String nativeClassifier) {
        private static PlatformBits current() {
            String architecture = System.getProperty("os.arch", "").toLowerCase();
            String bits = architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
            String osName = PlatformUtil.current().minecraftName();
            return new PlatformBits(osName, bits,
                    osName + "-" + FileUtil.nativeArchitecture(architecture));
        }
    }
}
