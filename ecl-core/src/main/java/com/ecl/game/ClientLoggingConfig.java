package com.ecl.game;

import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.JvmArgumentPolicy;
import com.ecl.util.NetworkUriPolicy;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The {@code logging.client} block used to load the version's declared Log4j configuration.
 *
 * <p>Everything here is derived from untrusted version metadata, so the shape is validated once at
 * parse time and the target path is always {@code <assets>/log_configs/<id>} — metadata can never
 * point the launcher at an arbitrary file. The download itself is verified by the declared SHA-1
 * and size before it replaces anything on disk.</p>
 *
 * @param file         the downloadable XML config as declared by the version metadata
 * @param argument     the JVM argument containing the {@code ${path}} placeholder
 * @param relativePath path of the config relative to the assets root, always inside {@code log_configs}
 */
public record ClientLoggingConfig(DownloadObject file, String argument, String relativePath) {

    /** The only placeholder the metadata argument may reference; the launcher fills in the path. */
    public static final String PATH_PLACEHOLDER = "${path}";

    /** Fixed directory under the assets root, matching the official launcher layout. */
    public static final String LOG_CONFIG_DIRECTORY = "log_configs";

    private static final long MAX_CONFIG_BYTES = 16L * 1024 * 1024;
    private static final Pattern SHA1 = Pattern.compile("(?i)[0-9a-f]{40}");

    public ClientLoggingConfig {
        if (file == null) {
            throw new IllegalArgumentException("Client logging config is missing its file");
        }
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("Client logging config is missing its argument");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Client logging config is missing its relative path");
        }
    }

    /**
     * Parse the {@code logging.client} object.
     *
     * @return the parsed config, or {@code null} when the metadata declares no client logging block
     * @throws IOException when the block is present but unsafe or incomplete
     */
    public static ClientLoggingConfig parse(JsonObject client) throws IOException {
        if (client == null) {
            return null;
        }
        String argument = JsonUtil.getString(client, "argument", "");
        requireValidArgument(argument);
        JsonObject fileObject = client.has("file") && client.get("file").isJsonObject()
                ? client.getAsJsonObject("file") : null;
        if (fileObject == null) {
            throw new IOException("logging.client.file 缺失或不是对象");
        }
        String id = JsonUtil.getString(fileObject, "id", "");
        FileUtil.requireSafeVersionId(id);
        String sha1 = JsonUtil.getString(fileObject, "sha1", "");
        if (!SHA1.matcher(sha1).matches()) {
            throw new IOException("logging.client.file.sha1 不是合法的 SHA-1: " + id);
        }
        long size = JsonUtil.getLong(fileObject, "size", -1L);
        if (size <= 0 || size > MAX_CONFIG_BYTES) {
            throw new IOException("logging.client.file.size 必须为正数: " + id);
        }
        String url = JsonUtil.getString(fileObject, "url", "");
        requireArtifactUrl(url);
        DownloadObject download = new DownloadObject(id, id, url, sha1, size);
        return new ClientLoggingConfig(download, argument,
                LOG_CONFIG_DIRECTORY + "/" + id);
    }

    /**
     * Structural URL check used at parse time. It must not resolve DNS: metadata is parsed while
     * offline and for every legacy profile, so the host is only checked for shape here and the full
     * {@link NetworkUriPolicy#requireArtifactDownload} policy is applied right before downloading.
     */
    private static void requireArtifactUrl(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("logging.client.file.url 为空");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("logging.client.file.url 不是合法 URL: " + url, invalid);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme) && NetworkUriPolicy.isLoopbackHostLiteral(host);
        if (host == null || host.isBlank() || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(scheme) || loopbackHttp)) {
            throw new IOException("logging.client.file.url 必须使用 HTTPS: " + url);
        }
    }

    /** The {@code ${path}} token replaced with the absolute config path. */
    public String substitute(String absolutePath) {
        return argument.replace(PATH_PLACEHOLDER, absolutePath);
    }

    /** Target file under the given assets directory; never escapes {@code log_configs}. */
    public File targetFile(File assetsDirectory) throws IOException {
        return FileUtil.safeResolveUnder(assetsDirectory, relativePath);
    }

    /**
     * Make sure the config exists under {@code <assets>/log_configs/} and matches the declared size
     * and SHA-1. An intact file is reused; anything else is downloaded to a temporary file and only
     * promoted after verification, so a failed download never destroys the existing file.
     *
     * @return the verified config file
     * @throws IOException when the config cannot be downloaded or fails verification
     */
    public File ensureDownloaded(File assetsDirectory) throws IOException {
        File target = targetFile(assetsDirectory);
        FileUtil.validateExistingAncestors(assetsDirectory.toPath(), target.toPath());
        if (isCurrent(target)) {
            return target;
        }
        Path targetPath = target.toPath();
        Path assetsPath = assetsDirectory.toPath().toAbsolutePath().normalize();
        FileUtil.validateExistingAncestors(assetsPath, targetPath);
        Path parent = targetPath.getParent();
        if (parent == null) {
            throw new IOException("logging.client.file 目标路径无效");
        }
        Files.createDirectories(parent);
        URI source = NetworkUriPolicy.requireArtifactDownload(
                URI.create(file.url()), "Minecraft client logging config");
        Path temporary = Files.createTempFile(parent, "log-config-", ".tmp");
        try {
            HttpUtil.downloadFileWithProgress(source.toString(), temporary.toFile(), null, null, file.size());
            requireCurrent(temporary.toFile());
            FileUtil.moveReplacing(temporary, targetPath);
            return target;
        } finally {
            Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + ".part.meta"));
            Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + ".part"));
            Files.deleteIfExists(temporary);
        }
    }

    /** True when {@code file} exists and matches both the declared size and SHA-1. */
    public boolean isCurrent(File file) throws IOException {
        if (file == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return file.length() == file().size() && FileUtil.verifySha1(file, file().sha1());
    }

    private void requireCurrent(File downloaded) throws IOException {
        if (!downloaded.isFile() || downloaded.length() != file.size()
                || !FileUtil.verifySha1(downloaded, file.sha1())) {
            throw new IOException("客户端日志配置校验失败: " + file.fileName());
        }
    }

    private static void requireValidArgument(String argument) throws IOException {
        if (argument == null || argument.isBlank()) {
            throw new IOException("logging.client.argument 为空");
        }
        if (!argument.contains(PATH_PLACEHOLDER)) {
            throw new IOException("logging.client.argument 必须包含 " + PATH_PLACEHOLDER);
        }
        if (argument.replace(PATH_PLACEHOLDER, "").contains("${")) {
            throw new IOException("logging.client.argument 不能包含其它占位符: " + argument);
        }
        try {
            JvmArgumentPolicy.requireSafe(argument);
        } catch (IllegalArgumentException unsafe) {
            throw new IOException("logging.client.argument 不被允许: " + argument, unsafe);
        }
    }
}
