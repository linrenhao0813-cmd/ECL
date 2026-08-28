package com.ecl.modrinth.download;

import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Shared validation and notification helpers used by the content downloader. */
final class ModrinthDownloadSupport {
    private ModrinthDownloadSupport() {
    }

    static void ensureCompatibleVersion(ModVersion version, String gameVersion, String loader)
            throws IOException {
        if (version == null) {
            throw new IOException("Modrinth 版本信息为空");
        }
        if (gameVersion != null && !gameVersion.isBlank()
                && version.gameVersions().stream().noneMatch(gameVersion::equalsIgnoreCase)) {
            throw new IOException("Modrinth 版本与目标 Minecraft " + gameVersion + " 不兼容");
        }
        if (loader != null && !loader.isBlank()
                && version.loaders().stream().noneMatch(loader::equalsIgnoreCase)) {
            throw new IOException("Modrinth 版本与目标加载器 " + loader + " 不兼容");
        }
    }

    static File requireTargetDirectory(File targetDir) throws IOException {
        if (targetDir == null) {
            throw new IOException("导入目录无效");
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("无法创建导入目录: " + targetDir.getAbsolutePath());
        }
        if (!targetDir.isDirectory()) {
            throw new IOException("导入目录无效: " + targetDir.getAbsolutePath());
        }
        return targetDir;
    }

    static void requireProject(ContentProject project) throws IOException {
        if (project == null) {
            throw new IOException("请选择一个下载项目");
        }
    }

    static String requireText(String value, String message) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(message);
        }
        return value.trim();
    }

    static String sanitizeFilename(String filename) {
        return filename == null ? null : TextUtil.replaceInvalidFilenameChars(filename);
    }

    static void notifyStatus(ModrinthDownloader.DownloadListener listener, String message) {
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    static void notifyProgress(ModrinthDownloader.DownloadListener listener, long downloaded, long total) {
        if (listener != null) {
            listener.onProgress(downloaded, total);
        }
    }

    static <T> T await(CompletableFuture<T> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            String message = cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new IOException(message, cause);
        }
    }
}
