package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.ecl.util.FileUtil;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;

/**
 * Installs launchable Fabric, Quilt, Forge and NeoForge profiles into ECL's
 * shared version/library store.
 */
public final class ModLoaderInstaller {
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3";
    private static final String FORGE_META =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String NEOFORGE_META =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final Pattern XML_VERSION = Pattern.compile("<version>([^<]+)</version>");

    public enum Loader {
        FABRIC("fabric", "Fabric"),
        QUILT("quilt", "Quilt"),
        FORGE("forge", "Forge"),
        NEOFORGE("neoforge", "NeoForge");

        private final String id;
        private final String displayName;

        Loader(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    public interface Listener {
        void onStatus(String message);

        default void onProgress(long downloaded, long total) {
        }
    }

    public record InstallResult(String profileId, String minecraftVersion, Loader loader,
                                String loaderVersion) {
    }

    public InstallResult install(String minecraftVersion, Loader loader, String requestedLoaderVersion,
                                 Listener listener) throws IOException {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("Minecraft 版本不能为空");
        }
        Listener safeListener = listener == null ? message -> { } : listener;
        safeListener.onStatus("正在查询 " + loader.displayName() + " 可用版本...");
        return switch (loader) {
            case FABRIC, QUILT ->
                    installProfileLoader(minecraftVersion.trim(), loader, requestedLoaderVersion, safeListener);
            case FORGE, NEOFORGE ->
                    installInstallerLoader(minecraftVersion.trim(), loader, requestedLoaderVersion, safeListener);
        };
    }

    public List<String> listVersions(String minecraftVersion, Loader loader) throws IOException {
        return switch (loader) {
            case FABRIC, QUILT -> listProfileLoaderVersions(minecraftVersion, loader);
            case FORGE -> listMavenVersions(FORGE_META).stream()
                    .filter(version -> version.startsWith(minecraftVersion + "-"))
                    .map(version -> version.substring(minecraftVersion.length() + 1))
                    .toList();
            case NEOFORGE -> {
                String prefix = neoForgePrefix(minecraftVersion);
                yield listMavenVersions(NEOFORGE_META).stream()
                        .filter(version -> version.startsWith(prefix))
                        .toList();
            }
        };
    }

    private InstallResult installProfileLoader(String minecraftVersion, Loader loader,
                                               String requestedVersion, Listener listener) throws IOException {
        List<String> versions = listProfileLoaderVersions(minecraftVersion, loader);
        String loaderVersion = selectVersion(versions, requestedVersion, loader);
        String base = loader == Loader.FABRIC ? FABRIC_META : QUILT_META;
        String profileUrl = base + "/versions/loader/" + encode(minecraftVersion) + "/"
                + encode(loaderVersion) + "/profile/json";
        listener.onStatus("正在安装 " + loader.displayName() + " " + loaderVersion + "...");
        JsonObject profile = HttpUtil.getJson(profileUrl);
        String profileId = JsonUtil.getString(profile, "id", "");
        if (profileId.isBlank()) {
            profileId = loader.id() + "-loader-" + loaderVersion + "-" + minecraftVersion;
            profile.addProperty("id", profileId);
        }
        profile.addProperty("eclModLoader", loader.id());
        profile.addProperty("eclModLoaderVersion", loaderVersion);
        profile.addProperty("eclMinecraftVersion", minecraftVersion);
        Path profileDir = safeProfileDirectory(profileId);
        Files.createDirectories(profileDir);
        HttpUtil.writeJson(profileDir.resolve(profileId + ".json").toFile(), profile);
        listener.onStatus(loader.displayName() + " " + loaderVersion + " 安装完成");
        return new InstallResult(profileId, minecraftVersion, loader, loaderVersion);
    }

    private List<String> listProfileLoaderVersions(String minecraftVersion, Loader loader) throws IOException {
        String base = loader == Loader.FABRIC ? FABRIC_META : QUILT_META;
        String body = HttpUtil.get(base + "/versions/loader/" + encode(minecraftVersion));
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonArray()) {
            throw new IOException(loader.displayName() + " 元数据格式无效");
        }
        List<String> stableVersions = new ArrayList<>();
        List<String> unstableVersions = new ArrayList<>();
        for (JsonElement item : parsed.getAsJsonArray()) {
            JsonObject object = item.getAsJsonObject();
            JsonObject loaderObject = object.has("loader") && object.get("loader").isJsonObject()
                    ? object.getAsJsonObject("loader") : object;
            String version = JsonUtil.getString(loaderObject, "version", "");
            if (!version.isBlank()) {
                boolean stable = !loaderObject.has("stable")
                        || loaderObject.get("stable").getAsBoolean();
                (stable ? stableVersions : unstableVersions).add(version);
            }
        }
        stableVersions.sort(ModLoaderInstaller::compareVersionsDescending);
        unstableVersions.sort(ModLoaderInstaller::compareVersionsDescending);
        List<String> versions = new ArrayList<>(stableVersions);
        versions.addAll(unstableVersions);
        if (versions.isEmpty()) {
            throw new IOException("没有找到兼容 Minecraft " + minecraftVersion + " 的 "
                    + loader.displayName() + " 版本");
        }
        return versions;
    }

    private InstallResult installInstallerLoader(String minecraftVersion, Loader loader,
                                                 String requestedVersion, Listener listener) throws IOException {
        List<String> versions = listVersions(minecraftVersion, loader);
        String loaderVersion = selectVersion(versions, requestedVersion, loader);
        String artifactVersion = loader == Loader.FORGE
                ? minecraftVersion + "-" + loaderVersion : loaderVersion;
        String artifactBase = loader == Loader.FORGE
                ? "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                : "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
        String artifactName = loader == Loader.FORGE ? "forge" : "neoforge";
        String installerUrl = artifactBase + artifactVersion + "/" + artifactName + "-"
                + artifactVersion + "-installer.jar";

        Files.createDirectories(ECLConfig.getBaseDir().toPath());
        Path workDir = Files.createTempDirectory(ECLConfig.getBaseDir().toPath(), "loader-install-");
        Path installer = workDir.resolve(artifactName + "-installer.jar");
        Path minecraftDir = workDir.resolve("minecraft");
        try {
            Files.createDirectories(minecraftDir);
            Files.writeString(minecraftDir.resolve("launcher_profiles.json"),
                    "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);
            copyBaseVersionIfPresent(minecraftVersion, minecraftDir.resolve("versions"));

            listener.onStatus("正在下载 " + loader.displayName() + " " + loaderVersion + " 安装器...");
            HttpUtil.downloadFileWithProgress(installerUrl, installer.toFile(),
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
                    });
            String expectedSha1 = HttpUtil.get(installerUrl + ".sha1").trim().split("\\s+")[0];
            verifyDigest(installer, "SHA-1", expectedSha1);

            int requiredJava = requiredJavaForMinecraft(minecraftVersion);
            String java = JavaRuntimeUtil.resolveOrDownloadJavaExecutable("", requiredJava,
                    listener::onStatus, listener::onProgress);
            listener.onStatus("正在运行 " + loader.displayName() + " 官方安装器...");
            runInstaller(java, installer, minecraftDir, loader);

            Path installedProfile = findInstalledProfile(minecraftDir.resolve("versions"),
                    minecraftVersion, loader);
            mergeDirectory(minecraftDir.resolve("libraries"), ECLConfig.getLibrariesDir().toPath());
            String profileId = installedProfile.getFileName().toString();
            Path targetProfile = safeProfileDirectory(profileId);
            mergeDirectory(installedProfile, targetProfile);
            annotateProfile(targetProfile.resolve(profileId + ".json"), minecraftVersion,
                    loader, loaderVersion);
            listener.onStatus(loader.displayName() + " " + loaderVersion + " 安装完成");
            return new InstallResult(profileId, minecraftVersion, loader, loaderVersion);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void runInstaller(String java, Path installer, Path minecraftDir, Loader loader)
            throws IOException {
        String installArgument = installerArgument(loader);
        Process process = new ProcessBuilder(java, "-jar", installer.toString(),
                installArgument, minecraftDir.toString())
                .directory(installer.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        StringBuffer output = new StringBuffer();
        Thread drain = Thread.ofVirtual().name("ecl-loader-installer-output").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 24_000) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("加载器安装器运行超过 10 分钟，已终止");
            }
            drain.join(Duration.ofSeconds(5));
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("加载器安装器退出码 " + exitCode + "："
                        + tail(output.toString(), 2_000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("加载器安装被中断", e);
        }
    }

    private Path findInstalledProfile(Path versionsDir, String minecraftVersion, Loader loader)
            throws IOException {
        if (!Files.isDirectory(versionsDir)) {
            throw new IOException("官方安装器没有生成版本目录");
        }
        try (var stream = Files.list(versionsDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().equals(minecraftVersion))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.contains(loader.id())
                                || (loader == Loader.FORGE && name.contains("forge"));
                    })
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IOException("官方安装器没有生成可识别的 "
                            + loader.displayName() + " 版本"));
        }
    }

    private void annotateProfile(Path jsonFile, String minecraftVersion, Loader loader,
                                 String loaderVersion) throws IOException {
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("加载器版本缺少 JSON: " + jsonFile);
        }
        JsonObject json = HttpUtil.readJson(jsonFile.toFile());
        json.addProperty("eclModLoader", loader.id());
        json.addProperty("eclModLoaderVersion", loaderVersion);
        json.addProperty("eclMinecraftVersion", minecraftVersion);
        HttpUtil.writeJson(jsonFile.toFile(), json);
    }

    private void copyBaseVersionIfPresent(String minecraftVersion, Path targetVersions) throws IOException {
        FileUtil.requireSafeVersionId(minecraftVersion);
        Path source = FileUtil.safeVersionDirectory(
                ECLConfig.getVersionsDir(), minecraftVersion).toPath();
        if (Files.isDirectory(source)) {
            mergeDirectory(source,
                    FileUtil.safeResolveUnder(targetVersions.toFile(), minecraftVersion).toPath());
        }
    }

    private static List<String> listMavenVersions(String metadataUrl) throws IOException {
        Matcher matcher = XML_VERSION.matcher(HttpUtil.get(metadataUrl));
        List<String> versions = new ArrayList<>();
        while (matcher.find()) {
            versions.add(matcher.group(1).trim());
        }
        versions.sort(ModLoaderInstaller::compareVersionsDescending);
        return versions;
    }

    private static String neoForgePrefix(String minecraftVersion) throws IOException {
        String[] parts = minecraftVersion.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            throw new IOException("NeoForge 不支持该 Minecraft 版本格式: " + minecraftVersion);
        }
        String patch = parts.length >= 3 ? parts[2].replaceAll("\\D.*$", "") : "0";
        return parts[1] + "." + (patch.isBlank() ? "0" : patch) + ".";
    }

    private static String selectVersion(List<String> versions, String requested, Loader loader)
            throws IOException {
        if (requested != null && !requested.isBlank()) {
            if (!versions.contains(requested.trim())) {
                throw new IOException(loader.displayName() + " 版本不可用: " + requested);
            }
            return requested.trim();
        }
        if (versions.isEmpty()) {
            throw new IOException("没有找到可用的 " + loader.displayName() + " 版本");
        }
        return versions.getFirst();
    }

    static String installerArgument(Loader loader) {
        return loader == Loader.NEOFORGE ? "--install-client" : "--installClient";
    }

    static int compareVersionsDescending(String left, String right) {
        boolean leftPrerelease = isPrerelease(left);
        boolean rightPrerelease = isPrerelease(right);
        if (leftPrerelease != rightPrerelease) {
            return leftPrerelease ? 1 : -1;
        }
        Matcher leftNumbers = Pattern.compile("\\d+").matcher(stableCore(left));
        Matcher rightNumbers = Pattern.compile("\\d+").matcher(stableCore(right));
        boolean leftHasNumber = leftNumbers.find();
        boolean rightHasNumber = rightNumbers.find();
        while (leftHasNumber || rightHasNumber) {
            int leftValue = leftHasNumber ? Integer.parseInt(leftNumbers.group()) : 0;
            int rightValue = rightHasNumber ? Integer.parseInt(rightNumbers.group()) : 0;
            int comparison = Integer.compare(rightValue, leftValue);
            if (comparison != 0) {
                return comparison;
            }
            leftHasNumber = leftNumbers.find();
            rightHasNumber = rightNumbers.find();
        }
        return right.compareToIgnoreCase(left);
    }

    private static String stableCore(String version) {
        return version.split("(?i)[._+-]?(?:alpha|beta|rc|snapshot)", 2)[0];
    }

    private static boolean isPrerelease(String version) {
        String value = version.toLowerCase(Locale.ROOT);
        return value.contains("alpha") || value.contains("beta") || value.contains("-rc")
                || value.contains("snapshot");
    }

    private static void verifyDigest(Path file, String algorithm, String expected)
            throws IOException {
        if (expected == null || expected.isBlank()) {
            throw new IOException("加载器安装器缺少发布方校验值");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(file);
                throw new IOException("加载器安装器校验失败");
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("不支持安装器校验算法 " + algorithm, error);
        }
    }

    private static Path safeProfileDirectory(String profileId) throws IOException {
        return FileUtil.safeVersionDirectory(ECLConfig.getVersionsDir(), profileId).toPath();
    }

    private static void mergeDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        try (var stream = Files.walk(source)) {
            for (Path item : stream.toList()) {
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target.toAbsolutePath().normalize())
                        && target.isAbsolute()) {
                    throw new IOException("安装器输出路径越界: " + relative);
                }
                if (Files.isDirectory(item)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Path temp = Files.createTempFile(destination.getParent(),
                            ".ecl-copy-", ".tmp");
                    try {
                        Files.copy(item, temp, StandardCopyOption.REPLACE_EXISTING);
                        try {
                            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Temporary installer files are safe to leave for a later cleanup.
        }
    }

    private static int requiredJavaForMinecraft(String version) {
        String[] parts = version.split("\\.");
        try {
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].replaceAll("\\D.*$", "")) : 0;
            if (minor > 20 || (minor == 20 && patch >= 5)) return 21;
            if (minor >= 18) return 17;
        } catch (NumberFormatException ignored) {
        }
        return 8;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String tail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
    }
}
