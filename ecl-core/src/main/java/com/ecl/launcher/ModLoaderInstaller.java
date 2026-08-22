package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import com.ecl.util.FileUtil;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Installs launchable Fabric, Quilt, Forge and NeoForge profiles into ECL's
 * shared version/library store.
 */
public final class ModLoaderInstaller {
    private final LoaderMetadataClient metadataClient = new LoaderMetadataClient();
    private final InstallerProcessRunner installerProcessRunner = new InstallerProcessRunner();
    private final LoaderArtifactVerifier artifactVerifier = new LoaderArtifactVerifier();
    private final LoaderProfileWriter profileWriter = new LoaderProfileWriter();

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
        return metadataClient.listVersions(minecraftVersion, loader);
    }

    private InstallResult installProfileLoader(String minecraftVersion, Loader loader,
                                               String requestedVersion, Listener listener) throws IOException {
        List<String> versions = metadataClient.listVersions(minecraftVersion, loader);
        String loaderVersion = selectVersion(versions, requestedVersion, loader);
        String profileUrl = metadataClient.profileUrl(minecraftVersion, loader, loaderVersion);
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
            artifactVerifier.verify(installer, "SHA-1", expectedSha1);

            int requiredJava = requiredJavaForMinecraft(minecraftVersion);
            String java = JavaRuntimeUtil.resolveOrDownloadJavaExecutable("", requiredJava,
                    listener::onStatus, listener::onProgress);
            listener.onStatus("正在运行 " + loader.displayName() + " 官方安装器...");
            installerProcessRunner.run(java, installer, minecraftDir, loader);

            Path installedProfile = findInstalledProfile(minecraftDir.resolve("versions"),
                    minecraftVersion, loader);
            mergeDirectory(minecraftDir.resolve("libraries"), ECLConfig.getLibrariesDir().toPath());
            String profileId = installedProfile.getFileName().toString();
            Path targetProfile = safeProfileDirectory(profileId);
            mergeDirectory(installedProfile, targetProfile);
            profileWriter.annotateProfile(targetProfile.resolve(profileId + ".json"),
                    minecraftVersion, loader, loaderVersion);
            listener.onStatus(loader.displayName() + " " + loaderVersion + " 安装完成");
            return new InstallResult(profileId, minecraftVersion, loader, loaderVersion);
        } finally {
            deleteRecursively(workDir);
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

    private void copyBaseVersionIfPresent(String minecraftVersion, Path targetVersions) throws IOException {
        FileUtil.requireSafeVersionId(minecraftVersion);
        Path source = FileUtil.safeVersionDirectory(
                ECLConfig.getVersionsDir(), minecraftVersion).toPath();
        if (Files.isDirectory(source)) {
            mergeDirectory(source,
                    FileUtil.safeResolveUnder(targetVersions.toFile(), minecraftVersion).toPath());
        }
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
        return LoaderMetadataClient.compareVersionsDescending(left, right);
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

}
