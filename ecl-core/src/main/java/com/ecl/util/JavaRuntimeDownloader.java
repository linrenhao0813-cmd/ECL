package com.ecl.util;

import com.ecl.ECLConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Downloads and extracts launcher-managed Eclipse Temurin JREs. */
final class JavaRuntimeDownloader {
    private static final String COMPLETE_MARKER = ".ecl-runtime-complete";
    private static final long MAX_RUNTIME_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final ZipUtil.ExtractionLimits RUNTIME_EXTRACTION_LIMITS =
            new ZipUtil.ExtractionLimits(
                    4L * 1024 * 1024 * 1024,
                    1024L * 1024 * 1024,
                    50_000,
                    200.0);
    private static final Map<String, Object> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();

    private JavaRuntimeDownloader() {
    }

    static String download(int featureVersion, Consumer<String> status,
                           BiConsumer<Long, Long> progress) throws IOException {
        String os = "windows";
        String arch = apiArch();
        String key = featureVersion + "-" + os + "-" + arch;
        synchronized (DOWNLOAD_LOCKS.computeIfAbsent(key, ignored -> new Object())) {
            return downloadLocked(featureVersion, os, arch, status, progress);
        }
    }

    private static String downloadLocked(int featureVersion, String os, String arch,
                                         Consumer<String> status,
                                         BiConsumer<Long, Long> progress) throws IOException {
        String extension = ".zip";
        Path runtimesRoot = ECLConfig.getBaseDir().toPath().resolve("runtimes");
        Path target = runtimesRoot.resolve("temurin-" + featureVersion + "-" + os + "-" + arch);
        Path existing = findJava(target);
        if (existing != null && Files.isRegularFile(target.resolve(COMPLETE_MARKER))
                && JavaRuntimeUtil.detectJavaFeatureVersion(existing.toFile()) == featureVersion) {
            return existing.toAbsolutePath().toString();
        }

        Files.createDirectories(runtimesRoot);
        if (Files.exists(target) && !deleteRecursively(target)) {
            throw new IOException("Existing Java runtime directory could not be removed: " + target);
        }
        Path archive = Files.createTempFile(runtimesRoot, "java-" + featureVersion + "-", extension);
        Path staging = Files.createTempDirectory(runtimesRoot, "java-extract-");
        Path publish = Files.createTempDirectory(runtimesRoot, "java-publish-");
        try {
            PackageInfo packageInfo = resolvePackage(featureVersion, os, arch);
            status.accept("正在下载 Java " + featureVersion + " 运行时...");
            HttpUtil.downloadFileWithProgress(packageInfo.url, archive.toFile(),
                    new HttpUtil.ProgressCallback() {
                @Override
                public void onStart(long total) {
                    progress.accept(0L, total);
                }

                @Override
                public void onProgress(long downloaded, long total) {
                    progress.accept(downloaded, total);
                }

                @Override
                public void onComplete(File file) {
                }
                    }, null, packageInfo.size);
            if (Files.size(archive) != packageInfo.size) {
                throw new IOException("Java runtime archive size does not match metadata");
            }
            verifySha256(archive, packageInfo.sha256);
            status.accept("正在解压 Java " + featureVersion + " 运行时...");
            extractZip(archive, staging);
            Path java = findJava(staging);
            if (java == null) {
                throw new IOException("下载的 Java 运行时中没有找到 java 可执行文件");
            }
            Path runtimeHome = java.getParent().getParent();
            copyDirectory(runtimeHome, publish);
            Path candidate = findJava(publish);
            if (candidate == null
                    || JavaRuntimeUtil.detectJavaFeatureVersion(candidate.toFile()) != featureVersion) {
                throw new IOException("Java 运行时安装后校验失败");
            }
            candidate.toFile().setExecutable(true, false);
            Files.writeString(publish.resolve(COMPLETE_MARKER),
                    "Java " + featureVersion, StandardCharsets.UTF_8);
            try {
                Files.move(publish, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(publish, target);
            }
            Path installed = findJava(target);
            status.accept("Java " + featureVersion + " 已安装到启动器运行时目录");
            return installed.toAbsolutePath().toString();
        } finally {
            Files.deleteIfExists(archive);
            deleteRecursively(staging);
            deleteRecursively(publish);
        }
    }

    private static PackageInfo resolvePackage(int featureVersion, String os, String arch)
            throws IOException {
        String assetsUrl = "https://api.adoptium.net/v3/assets/latest/" + featureVersion
                + "/hotspot?architecture=" + arch
                + "&heap_size=normal&image_type=jre&jvm_impl=hotspot&os=" + os
                + "&vendor=eclipse";
        JsonArray assets;
        try {
            assets = JsonParser.parseString(HttpUtil.get(assetsUrl)).getAsJsonArray();
        } catch (RuntimeException error) {
            throw new IOException("Adoptium 返回了无效的 Java 运行时元数据", error);
        }
        for (var item : assets) {
            JsonObject binary = item.getAsJsonObject().getAsJsonObject("binary");
            if (binary == null || !binary.has("package")) continue;
            JsonObject packageObject = binary.getAsJsonObject("package");
            String link = packageObject.has("link") ? packageObject.get("link").getAsString() : "";
            String checksum = packageObject.has("checksum")
                    ? packageObject.get("checksum").getAsString() : "";
            long size = packageObject.has("size")
                    ? JsonUtil.getLong(packageObject, "size", -1L) : -1L;
            if (!link.isBlank() && checksum.matches("(?i)[0-9a-f]{64}")
                    && size > 0 && size <= MAX_RUNTIME_ARCHIVE_BYTES) {
                NetworkUriPolicy.requireHttps(java.net.URI.create(link),
                        "Java runtime download URL");
                return new PackageInfo(link, checksum, size);
            }
        }
        throw new IOException("Adoptium 没有提供兼容的 Java " + featureVersion + " JRE");
    }

    private static void verifySha256(Path file, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("Java 运行时 SHA-256 校验失败");
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("当前 Java 不支持 SHA-256 校验", error);
        }
    }

    private static void extractZip(Path archive, Path target) throws IOException {
        extractZip(archive, target, RUNTIME_EXTRACTION_LIMITS);
    }

    static void extractZip(Path archive, Path target, ZipUtil.ExtractionLimits limits)
            throws IOException {
        ZipUtil.extractSafely(archive, target, null, limits);
    }

    private static Path findJava(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var stream = Files.walk(root, 5)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("java.exe"))
                    .filter(path -> path.getParent() != null
                            && "bin".equalsIgnoreCase(path.getParent().getFileName().toString()))
                    .findFirst().orElse(null);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path)).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Java 解压路径越界");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return true;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return !Files.exists(root);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String apiArch() throws IOException {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.contains("x86_64") || arch.contains("amd64")) return "x64";
        if (arch.equals("x86") || arch.contains("i386")) return "x86";
        throw new IOException("暂不支持自动下载 Java 的架构: " + arch);
    }

    private record PackageInfo(String url, String sha256, long size) {
    }
}
