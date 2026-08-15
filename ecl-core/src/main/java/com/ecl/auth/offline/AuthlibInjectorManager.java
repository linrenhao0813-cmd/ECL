package com.ecl.auth.offline;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

/**
 * Downloads and caches the authlib-injector agent jar used to inject offline skins into the game.
 *
 * <p>The jar is fetched once into the launcher data directory and reused for every launch. The
 * official artifact API is the primary source; the BMCLAPI mirror (China-friendly) is the
 * fallback, so users behind restrictive networks still get a usable binary.</p>
 */
public final class AuthlibInjectorManager {
    private static final Object CACHE_LOCK = new Object();
    private static final String LATEST_JSON_URL = "https://authlib-injector.yushi.moe/artifact/latest.json";
    private static final String BMCLAPI_MIRROR_URL =
            "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json";

    private final Path jarFile;
    private final Path checksumFile;

    public AuthlibInjectorManager() {
        this(ECLConfig.getBaseDir().toPath().resolve("authlib-injector.jar"));
    }

    AuthlibInjectorManager(Path jarFile) {
        this.jarFile = jarFile;
        this.checksumFile = jarFile.resolveSibling(jarFile.getFileName() + ".sha256");
    }

    /**
     * Return the cached agent jar, downloading it first if needed.
     *
     * @throws IOException if neither download source succeeds
     */
    public Path ensureJar() throws IOException {
        synchronized (CACHE_LOCK) {
            return ensureJarLocked();
        }
    }

    private Path ensureJarLocked() throws IOException {
        if (isCachedJarValid()) {
            return jarFile;
        }
        Files.createDirectories(jarFile.getParent());
        Path temp = Files.createTempFile(jarFile.getParent(), "authlib-injector-", ".jar");
        try {
            String checksum = downloadTo(temp);
            Files.move(temp, jarFile, StandardCopyOption.REPLACE_EXISTING);
            writeChecksum(checksum);
        } finally {
            Files.deleteIfExists(temp);
        }
        return jarFile;
    }

    private boolean isCachedJarValid() {
        try {
            if (!Files.isRegularFile(checksumFile)) {
                return false;
            }
            String checksum = Files.readString(checksumFile, StandardCharsets.US_ASCII).trim();
            return isUsableJar(jarFile, checksum);
        } catch (IOException failure) {
            return false;
        }
    }

    private String downloadTo(Path target) throws IOException {
        IOException lastFailure = null;
        for (String latestJsonUrl : List.of(LATEST_JSON_URL, BMCLAPI_MIRROR_URL)) {
            try {
                JsonObject meta = HttpUtil.getJson(latestJsonUrl);
                String url = meta == null || !meta.has("download_url") ? null
                        : meta.get("download_url").getAsString();
                String checksum = checksum(meta);
                if (url == null || url.isBlank()) {
                    throw new IOException("authlib-injector metadata has no download URL");
                }
                if (checksum == null) {
                    throw new IOException("authlib-injector metadata has no valid SHA-256 checksum");
                }
                HttpUtil.downloadFile(url, target.toFile());
                if (!isUsableJar(target, checksum)) {
                    throw new IOException("authlib-injector download failed integrity validation");
                }
                return checksum;
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(target);
                lastFailure = failure instanceof IOException ioFailure
                        ? ioFailure : new IOException(failure.getMessage(), failure);
            }
        }
        throw new IOException("无法下载 authlib-injector（离线皮肤服务需要此组件），请检查网络后重试", lastFailure);
    }

    private void writeChecksum(String checksum) throws IOException {
        Path temp = Files.createTempFile(checksumFile.getParent(), "authlib-injector-", ".sha256.tmp");
        try {
            Files.writeString(temp, checksum + System.lineSeparator(), StandardCharsets.US_ASCII);
            try {
                Files.move(temp, checksumFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, checksumFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String checksum(JsonObject meta) {
        if (meta == null || !meta.has("checksums") || !meta.get("checksums").isJsonObject()) {
            return null;
        }
        JsonObject checksums = meta.getAsJsonObject("checksums");
        if (!checksums.has("sha256") || checksums.get("sha256").isJsonNull()) {
            return null;
        }
        String checksum = checksums.get("sha256").getAsString().trim().toLowerCase(Locale.ROOT);
        return checksum.matches("[0-9a-f]{64}") ? checksum : null;
    }

    /** Accept only a complete Java agent JAR whose SHA-256 matches the artifact metadata. */
    static boolean isUsableJar(Path file, String expectedSha256) {
        try {
            if (file == null || !Files.isRegularFile(file)
                    || expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
                return false;
            }
            if (!sha256Hex(file).equalsIgnoreCase(expectedSha256)) {
                return false;
            }
            try (JarFile jar = new JarFile(file.toFile())) {
                return jar.getManifest() != null
                        && jar.getManifest().getMainAttributes().getValue("Premain-Class") != null;
            }
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
