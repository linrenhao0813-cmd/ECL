package com.ecl.modrinth.pack;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Downloads and installs the client files declared by an MRPACK index with hash verification. */
final class MrpackFileInstaller {
    private static final long MAX_INDEXED_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_INDEXED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_INDEXED_FILES = 100_000;
    private static final Set<String> TRUSTED_DOWNLOAD_HOSTS = Set.of("cdn.modrinth.com");

    private MrpackFileInstaller() {
    }

    static int installIndexedFiles(JsonObject index, Path instanceRoot,
                                   MrpackInstaller.Listener listener) throws IOException {
        return installIndexedFiles(index, instanceRoot, listener, TRUSTED_DOWNLOAD_HOSTS);
    }

    static int installIndexedFiles(JsonObject index, Path instanceRoot,
                                   MrpackInstaller.Listener listener,
                                   Set<String> trustedDownloadHosts) throws IOException {
        JsonArray files = index.has("files") && index.get("files").isJsonArray()
                ? index.getAsJsonArray("files") : new JsonArray();
        if (files.size() > MAX_INDEXED_FILES) {
            throw new IOException("MRPACK indexed file count exceeds the safety limit");
        }
        int completed = 0;
        long totalBytes = 0;
        for (JsonElement element : files) {
            JsonObject item = element.getAsJsonObject();
            if (!isClientFile(item)) {
                continue;
            }
            String relative = JsonUtil.getString(item, "path", "");
            Path destination = MrpackPathPolicy.safeResolve(instanceRoot, relative);
            long declaredSize = JsonUtil.getLong(item, "fileSize", -1);
            if (declaredSize < 0) {
                throw new IOException("MRPACK file is missing a valid fileSize: " + relative);
            }
            if (declaredSize > MAX_INDEXED_FILE_BYTES
                    || declaredSize > MAX_TOTAL_INDEXED_BYTES - totalBytes) {
                throw new IOException("MRPACK file exceeds the download safety limit: " + relative);
            }
            JsonArray downloads = item.has("downloads") && item.get("downloads").isJsonArray()
                    ? item.getAsJsonArray("downloads") : new JsonArray();
            if (downloads.isEmpty()) {
                throw new IOException("整合包文件没有下载地址: " + relative);
            }
            Files.createDirectories(destination.getParent());
            IOException lastError = null;
            for (JsonElement download : downloads) {
                try {
                    String url = requireTrustedDownloadUrl(
                            download.getAsString(), trustedDownloadHosts).toString();
                    listener.onStatus("正在下载整合包文件 " + (completed + 1) + "/" + files.size()
                            + ": " + relative);
                    long remainingBudget = Math.min(declaredSize,
                            MAX_TOTAL_INDEXED_BYTES - totalBytes);
                    HttpUtil.downloadFileWithProgress(url, destination.toFile(),
                            new HttpUtil.ProgressCallback() {
                                @Override
                                public void onStart(long total) {
                                    listener.onProgress(0, total);
                                }

                                @Override
                                public void onProgress(long downloaded, long total) {
                                    listener.onProgress(downloaded, total);
                                }

                                @Override
                                public void onComplete(File file) {
                                }
                            }, null, remainingBudget);
                    verifyHashes(destination, item);
                    long downloadedSize = Files.size(destination);
                    if (downloadedSize != declaredSize) {
                        Files.deleteIfExists(destination);
                        throw new IOException(
                                "MRPACK file size does not match its manifest: " + relative);
                    }
                    if (downloadedSize > MAX_INDEXED_FILE_BYTES
                            || downloadedSize > MAX_TOTAL_INDEXED_BYTES - totalBytes) {
                        Files.deleteIfExists(destination);
                        throw new IOException("整合包下载文件超过安全大小限制: " + relative);
                    }
                    totalBytes += downloadedSize;
                    lastError = null;
                    break;
                } catch (IOException error) {
                    lastError = error;
                    Files.deleteIfExists(destination);
                }
            }
            if (lastError != null) {
                throw new IOException("整合包文件下载失败: " + relative, lastError);
            }
            completed++;
        }
        return completed;
    }

    private static URI requireTrustedDownloadUrl(String value, Set<String> trustedHosts)
            throws IOException {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null
                    ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getUserInfo() != null || !trustedHosts.contains(host)) {
                throw new IOException("MRPACK download URL is not trusted: " + value);
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new IOException("MRPACK download URL is invalid: " + value, error);
        }
    }

    private static boolean isClientFile(JsonObject item) {
        if (!item.has("env") || !item.get("env").isJsonObject()) {
            return true;
        }
        String client = JsonUtil.getString(item.getAsJsonObject("env"), "client", "required");
        return !"unsupported".equalsIgnoreCase(client);
    }

    private static void verifyHashes(Path file, JsonObject item) throws IOException {
        if (!item.has("hashes") || !item.get("hashes").isJsonObject()) {
            return;
        }
        JsonObject hashes = item.getAsJsonObject("hashes");
        String algorithm;
        String expected;
        if (hashes.has("sha512")) {
            algorithm = "SHA-512";
            expected = hashes.get("sha512").getAsString();
        } else if (hashes.has("sha1")) {
            algorithm = "SHA-1";
            expected = hashes.get("sha1").getAsString();
        } else {
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("整合包文件校验失败: " + file.getFileName());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 Java 不支持 " + algorithm + " 校验", e);
        }
    }
}
