package com.ecl.download;

import com.ecl.game.DownloadObject;
import com.ecl.launcher.VersionManager;
import com.ecl.util.DownloadSourceUtil;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Resolves and downloads the official dedicated-server JAR declared by Minecraft metadata. */
public final class ServerJarDownloader {
    private final VersionManager versionManager;

    public ServerJarDownloader(VersionManager versionManager) {
        this.versionManager = versionManager;
    }

    public ServerArtifact resolve(String versionId, Listener listener) throws IOException {
        if (versionId == null || versionId.isBlank()) {
            throw new IOException("Minecraft 版本不能为空");
        }

        JsonObject metadata = versionManager.loadVersionJson(versionId);
        if (!hasServerDownload(metadata)) {
            String metadataUrl = versionManager.getVersionUrl(versionId);
            if (metadataUrl == null || metadataUrl.isBlank()) {
                throw new IOException("版本列表中没有 " + versionId + " 的元数据地址");
            }
            metadata = HttpUtil.getJson(metadataUrl);
        }

        JsonObject downloads = metadata.getAsJsonObject("downloads");
        JsonObject serverJson = downloads == null ? null : downloads.getAsJsonObject("server");
        DownloadObject server = DownloadObject.parse(serverJson);
        if (server == null || server.url() == null || server.url().isBlank()) {
            throw new IOException("Minecraft " + versionId + " 没有提供官方服务端文件");
        }
        if (server.sha1() == null || !server.sha1().matches("(?i)[0-9a-f]{40}")) {
            throw new IOException("Minecraft " + versionId + " 服务端缺少有效 SHA-1");
        }
        if (server.size() <= 0) {
            throw new IOException("Minecraft " + versionId + " 服务端缺少有效大小");
        }
        NetworkUriPolicy.requireSecureDownload(
                java.net.URI.create(server.url()), "Minecraft server download URL");
        List<DownloadChannel> channels = DownloadSourceUtil.candidates(server.url()).stream()
                .map(url -> new DownloadChannel(
                        DownloadSourceUtil.sourceName(url),
                        url,
                        DownloadSourceUtil.isMirror(server.url(), url)))
                .toList();
        return new ServerArtifact(versionId, server.url(), server.sha1(), server.size(), channels);
    }

    public File download(ServerArtifact artifact, File target, Listener listener) throws IOException {
        if (artifact == null) throw new IOException("尚未选择服务端文件");
        if (target == null) throw new IOException("服务端文件保存位置不能为空");

        if (target.isFile() && matches(target, artifact)) {
            if (listener != null) {
                listener.onProgress(target.length(), target.length());
                listener.onComplete(target);
            }
            return target;
        }

        HttpUtil.downloadFileWithProgress(artifact.url(), target, new HttpUtil.ProgressCallback() {
            @Override
            public void onStart(long total) {
                if (listener != null) listener.onStart(total);
            }

            @Override
            public void onProgress(long downloaded, long total) {
                if (listener != null) listener.onProgress(downloaded, total);
            }

            @Override
            public void onComplete(File file) {
                // Completion is reported only after metadata checks below succeed.
            }
        }, sourceCallback(listener), artifact.size());

        if (!matches(target, artifact)) {
            Files.deleteIfExists(target.toPath());
            throw new IOException(target.getName() + " 的大小或 SHA-1 校验失败");
        }
        if (listener != null) listener.onComplete(target);
        return target;
    }

    public static String suggestedFileName(String versionId) {
        String safeVersion = versionId == null ? "unknown" : versionId.trim()
                .replaceAll("[<>:\"/\\\\|?*]", "_")
                .replaceAll("[. ]+$", "");
        if (safeVersion.isBlank()) safeVersion = "unknown";
        return "minecraft_server." + safeVersion + ".jar";
    }

    private static boolean hasServerDownload(JsonObject metadata) {
        if (metadata == null) return false;
        JsonObject downloads = metadata.getAsJsonObject("downloads");
        return downloads != null && downloads.has("server") && downloads.get("server").isJsonObject();
    }

    private static boolean matches(File file, ServerArtifact artifact) {
        if (!file.isFile()) return false;
        if (artifact.size() >= 0 && file.length() != artifact.size()) return false;
        return artifact.sha1() != null && artifact.sha1().matches("(?i)[0-9a-f]{40}")
                && FileUtil.verifySha1(file, artifact.sha1());
    }

    private static HttpUtil.SourceCallback sourceCallback(Listener listener) {
        return new HttpUtil.SourceCallback() {
            @Override
            public void onSource(String originalUrl, String candidateUrl,
                                 boolean mirror, String sourceName) {
                if (listener != null) listener.onSource(sourceName, candidateUrl, mirror);
            }

            @Override
            public void onFailure(String candidateUrl, IOException error) {
                if (listener != null) listener.onSourceFailure(candidateUrl, error);
            }
        };
    }

    public record DownloadChannel(String name, String url, boolean mirror) {
    }

    public record ServerArtifact(
            String versionId,
            String url,
            String sha1,
            long size,
            List<DownloadChannel> channels
    ) {
    }

    public interface Listener {
        default void onStart(long total) {
        }

        default void onProgress(long downloaded, long total) {
        }

        default void onSource(String sourceName, String candidateUrl, boolean mirror) {
        }

        default void onSourceFailure(String candidateUrl, IOException error) {
        }

        default void onComplete(File file) {
        }
    }
}
