package com.ecl.download;

import com.ecl.game.DownloadObject;
import com.ecl.launcher.VersionManager;
import com.ecl.launch.JavaVersionRequirement;
import com.ecl.util.DownloadSourceUtil;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Resolves and downloads the official dedicated-server JAR declared by Minecraft metadata. */
public final class ServerJarDownloader {
    private final VersionManager versionManager;
    private final MetadataDownloader metadataDownloader;

    public ServerJarDownloader(VersionManager versionManager) {
        this(versionManager, (url, target) -> HttpUtil.downloadFileWithProgress(url, target.toFile(), null, null,
                4 * 1024 * 1024, Set.of("piston-meta.mojang.com", "launchermeta.mojang.com")));
    }

    ServerJarDownloader(VersionManager versionManager, MetadataDownloader metadataDownloader) {
        this.versionManager = versionManager;
        this.metadataDownloader = metadataDownloader;
    }

    @FunctionalInterface
    interface MetadataDownloader {
        void download(String url, Path target) throws IOException;
    }

    /** Reads the Java requirement from authoritative Mojang metadata, not a local client profile. */
    public int requiredJavaVersion(String versionId) throws IOException {
        return javaRequirement(officialMetadata(versionId), versionId);
    }

    /** Resolves only manifest-hash-bound official metadata; never trusts local client downloads. */
    public ServerArtifact resolveOfficial(String versionId, Listener listener) throws IOException {
        return artifact(versionId, officialMetadata(versionId));
    }

    private JsonObject officialMetadata(String versionId) throws IOException {
        FileUtil.requireSafeVersionId(versionId);
        var source = versionManager.resolveDownloadTarget(versionId);
        if (!versionId.equals(source.downloadVersionId()) || source.versionUrl() == null || source.versionUrl().isBlank()) {
            throw new IOException("No official metadata for " + versionId);
        }
        if (source.versionSha1() == null || !source.versionSha1().matches("(?i)[0-9a-f]{40}")) {
            throw new IOException("Official Minecraft metadata is missing its manifest SHA-1");
        }
        Path temporary = Files.createTempFile("ecl-server-metadata-", ".json");
        try {
            metadataDownloader.download(source.versionUrl(), temporary);
            if (!FileUtil.verifySha1(temporary.toFile(), source.versionSha1())) {
                throw new IOException("Official Minecraft metadata failed manifest SHA-1 verification");
            }
            JsonObject metadata = JsonParser.parseString(Files.readString(temporary)).getAsJsonObject();
            if (!versionId.equals(metadata.get("id").getAsString())) throw new IOException("Minecraft metadata version mismatch");
            return metadata;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Minecraft metadata", invalid);
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + ".part"));
            Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + ".part.meta"));
        }
    }

    static int javaRequirement(JsonObject metadata, String versionId) throws IOException {
        try {
            if (!versionId.equals(metadata.get("id").getAsString())) throw new IOException("Minecraft metadata version mismatch");
            JsonObject javaVersion = metadata.getAsJsonObject("javaVersion");
            int major = javaVersion == null ? JavaVersionRequirement.inferFromVersionId(versionId)
                    : javaVersion.get("majorVersion").getAsInt();
            if (major < 8 || major > 100) throw new IOException("Invalid Minecraft Java requirement");
            return major;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Minecraft Java metadata", invalid);
        }
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

        return artifact(versionId, metadata);
    }

    private static ServerArtifact artifact(String versionId, JsonObject metadata) throws IOException {
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
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Path targetParent = targetPath.getParent();
        if (targetParent == null) {
            throw new IOException("服务端文件保存位置没有父目录: " + target);
        }
        FileUtil.validateExistingAncestors(targetParent, targetPath);
        if (Files.isSymbolicLink(targetPath)) {
            throw new IOException("服务器 JAR 目标不能是符号链接: " + target);
        }
        Files.createDirectories(targetParent);

        if (target.isFile() && matches(target, artifact)) {
            if (listener != null) {
                listener.onProgress(target.length(), target.length());
                listener.onComplete(target);
            }
            return target;
        }

        Path temporary = Files.createTempFile(targetParent, ".ecl-server-", ".tmp");
        try {
            HttpUtil.downloadFileWithProgress(artifact.url(), temporary.toFile(), new HttpUtil.ProgressCallback() {
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

            if (!matches(temporary.toFile(), artifact)) {
                throw new IOException(target.getName() + " 的大小或 SHA-1 校验失败");
            }
            FileUtil.moveReplacing(temporary, targetPath);
            if (listener != null) listener.onComplete(target);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
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
