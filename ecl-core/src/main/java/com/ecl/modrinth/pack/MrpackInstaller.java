package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs a Modrinth .mrpack as an isolated, launchable ECL profile. */
public final class MrpackInstaller {
    private static final long MAX_OVERRIDE_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_OVERRIDE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_OVERRIDE_ENTRIES = 100_000;
    private static final double MAX_OVERRIDE_COMPRESSION_RATIO = 200.0;

    public interface Listener extends ModLoaderInstaller.Listener {
    }

    public record InstallResult(String profileId, String name, String version,
                                String minecraftVersion, String loader, Path instanceDirectory,
                                int downloadedFiles) {
    }

    @FunctionalInterface
    interface LoaderInstallation {
        ModLoaderInstaller.InstallResult install(
                String minecraftVersion,
                ModLoaderInstaller.Loader loader,
                String loaderVersion,
                ModLoaderInstaller.Listener listener
        ) throws IOException;
    }

    @FunctionalInterface
    interface TransactionFactory {
        PackUpdateTransaction create(Path instanceRoot, Path profileFile) throws IOException;
    }

    private final LoaderInstallation loaderInstallation;
    private final TransactionFactory transactionFactory;

    public MrpackInstaller() {
        this(new ModLoaderInstaller()::install, PackUpdateTransaction::new);
    }

    MrpackInstaller(ModLoaderInstaller loaderInstaller) {
        this(loaderInstaller::install, PackUpdateTransaction::new);
    }

    MrpackInstaller(LoaderInstallation loaderInstallation, TransactionFactory transactionFactory) {
        this.loaderInstallation = java.util.Objects.requireNonNull(
                loaderInstallation, "loaderInstallation");
        this.transactionFactory = java.util.Objects.requireNonNull(
                transactionFactory, "transactionFactory");
    }

    /** Install the client files and overrides from an MRPACK into an existing staging directory. */
    public int installContents(File archive, Path instanceRoot, Listener listener) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("MRPACK file does not exist");
        Path root = instanceRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Listener safeListener = listener == null ? message -> { } : listener;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            JsonObject index = MrpackIndexReader.read(zip,
                    "MRPACK index is missing or exceeds the safety limit",
                    "MRPACK index is missing or exceeds the safety limit",
                    "MRPACK index is invalid");
            if (!"1".equals(JsonUtil.getString(index, "formatVersion", ""))) {
                throw new IOException("Unsupported MRPACK format version");
            }
            JsonObject dependencies = MrpackDependencyResolver.requireDependencies(
                    index, "整合包索引缺少 dependencies");
            MrpackDependencyResolver.requireMinecraftVersion(
                    dependencies, "MRPACK does not declare a Minecraft version");
            int installed = MrpackFileInstaller.installIndexedFiles(index, root, safeListener);
            ExtractionBudget budget = new ExtractionBudget();
            installed += extractOverrides(zip, "overrides/", root, budget);
            installed += extractOverrides(zip, "client-overrides/", root, budget);
            return installed;
        }
    }

    public InstallResult install(File archive, File gameRoot, String preferredName,
                                 Listener listener) throws IOException {
        return install(archive, gameRoot, preferredName, "", "", listener);
    }

    /** Install a pack and retain the provider identity used by the update checker. */
    public InstallResult install(File archive, File gameRoot, String preferredName,
                                 String sourceProjectId, String sourceVersionId,
                                 Listener listener) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("整合包文件不存在");
        }
        if (gameRoot == null) {
            throw new IOException("游戏目录不能为空");
        }
        Listener safeListener = listener == null ? message -> { } : listener;
        JsonObject index;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            index = MrpackIndexReader.read(zip,
                    "该文件不是有效的 .mrpack：缺少 modrinth.index.json",
                    "整合包索引超过安全限制",
                    "整合包索引格式无效");

            String formatVersion = JsonUtil.getString(index, "formatVersion", "");
            if (!"1".equals(formatVersion)) {
                throw new IOException("暂不支持的 Modrinth 整合包格式版本: " + formatVersion);
            }
            JsonObject dependencies = MrpackDependencyResolver.requireDependencies(
                    index, "整合包索引缺少 dependencies");
            String minecraftVersion = MrpackDependencyResolver.requireMinecraftVersion(
                    dependencies, "整合包没有声明 Minecraft 版本");
            LoaderDependency loaderDependency = MrpackDependencyResolver.findLoader(dependencies);
            String parentProfile = minecraftVersion;
            if (loaderDependency != null) {
                safeListener.onStatus("正在准备整合包需要的 " + loaderDependency.loader().displayName()
                        + " " + loaderDependency.version() + "...");
                parentProfile = loaderInstallation.install(minecraftVersion, loaderDependency.loader(),
                        loaderDependency.version(), safeListener).profileId();
            }

            String name = preferredName == null || preferredName.isBlank()
                    ? JsonUtil.getString(index, "name", "Modrinth Pack") : preferredName.trim();
            String version = JsonUtil.getString(index, "versionId", "1");
            String profileId = MrpackPathPolicy.uniqueProfileId(name, version, gameRoot.toPath());
            Path instanceRoot = MrpackPathPolicy.safeInstanceDirectory(gameRoot.toPath(), profileId);
            Files.createDirectories(instanceRoot.getParent());
            Path staging = Files.createTempDirectory(instanceRoot.getParent(),
                    ".ecl-pack-install-" + profileId + "-");
            try {
                safeListener.onStatus("正在安装整合包文件...");
                int fileCount = MrpackFileInstaller.installIndexedFiles(index, staging, safeListener);
                ExtractionBudget extractionBudget = new ExtractionBudget();
                extractOverrides(zip, "overrides/", staging, extractionBudget);
                extractOverrides(zip, "client-overrides/", staging, extractionBudget);
                Files.copy(archive.toPath(), staging.resolve(profileId + ".mrpack"),
                        StandardCopyOption.REPLACE_EXISTING);
                PackManifest.capture(staging, version)
                        .write(staging.resolve(PackManifest.FILE_NAME));
                try {
                    Files.move(staging, instanceRoot, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveError) {
                    Files.move(staging, instanceRoot);
                }
                try {
                    writeProfile(profileId, parentProfile, name, version,
                            minecraftVersion, loaderDependency, sourceProjectId, sourceVersionId);
                } catch (IOException profileError) {
                    MrpackPathPolicy.deleteRecursively(instanceRoot);
                    MrpackPathPolicy.deleteProfile(profileId);
                    throw profileError;
                }
                safeListener.onStatus("整合包已安装为独立实例 " + profileId);
                return new InstallResult(profileId, name, version, minecraftVersion,
                        loaderDependency == null ? "" : loaderDependency.loader().id(),
                        instanceRoot, fileCount);
            } finally {
                MrpackPathPolicy.deleteRecursively(staging);
            }
        }
    }

    /**
     * Applies a newer MRPACK to an existing pack profile while preserving user files in the
     * instance directory. The pack archive and every file managed by the new index are replaced
     * through a staging directory; saves, screenshots and other user-created files are retained.
     */
    public InstallResult update(File archive, File gameRoot, String profileId,
                                String sourceProjectId, String sourceVersionId,
                                Listener listener) throws IOException {
        return update(archive, gameRoot, profileId, sourceProjectId, sourceVersionId,
                () -> false, listener);
    }

    /**
     * Applies a newer MRPACK as one journaled transaction. Files removed by the new pack are
     * deleted only when their current SHA-512 still matches the previous pack manifest.
     */
    public InstallResult update(File archive, File gameRoot, String profileId,
                                String sourceProjectId, String sourceVersionId,
                                BooleanSupplier instanceRunning,
                                Listener listener) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("MRPACK file does not exist");
        }
        if (gameRoot == null) {
            throw new IOException("Game directory must not be null");
        }
        String safeProfileId = profileId == null ? "" : profileId.trim();
        com.ecl.util.FileUtil.requireSafeVersionId(safeProfileId);
        Path gameRootPath = gameRoot.toPath().toAbsolutePath().normalize();
        Path instanceRoot = MrpackPathPolicy.safeInstanceDirectory(gameRootPath, safeProfileId);
        if (!Files.isDirectory(instanceRoot)) {
            throw new IOException("Modpack instance directory does not exist: " + instanceRoot);
        }
        Path profileFile = com.ecl.util.FileUtil.safeVersionJson(
                ECLConfig.getVersionsDir(), safeProfileId).toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(profileFile)) {
            throw new IOException("Modpack profile metadata does not exist: " + profileFile);
        }

        Listener safeListener = listener == null ? message -> { } : listener;
        BooleanSupplier runningGuard = instanceRunning == null ? () -> false : instanceRunning;
        JsonObject index;
        JsonObject dependencies;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            index = MrpackIndexReader.read(zip,
                    "MRPACK index is missing or exceeds the safety limit",
                    "MRPACK index is missing or exceeds the safety limit",
                    "MRPACK index is invalid");
            if (!"1".equals(JsonUtil.getString(index, "formatVersion", ""))) {
                throw new IOException("Unsupported MRPACK format version");
            }
            dependencies = MrpackDependencyResolver.requireDependencies(
                    index, "整合包索引缺少 dependencies");
        }
        String minecraftVersion = MrpackDependencyResolver.requireMinecraftVersion(
                dependencies, "MRPACK does not declare a Minecraft version");
        LoaderDependency loaderDependency = MrpackDependencyResolver.findLoader(dependencies);
        String packVersion = JsonUtil.getString(index, "versionId", sourceVersionId);
        JsonObject profile = HttpUtil.readJson(profileFile.toFile());
        PackManifest oldManifest = readInstalledManifest(instanceRoot, safeListener);

        PackUpdateTransaction.recoverIncompleteTransactions(instanceRoot, profileFile);
        try (PackUpdateTransaction transaction = transactionFactory.create(instanceRoot, profileFile)) {
            Path staging = transaction.stagingDirectory();
            safeListener.onStatus("正在准备整合包更新文件...");
            int fileCount = installContents(archive, staging, safeListener);
            Files.copy(archive.toPath(), staging.resolve(safeProfileId + ".mrpack"),
                    StandardCopyOption.REPLACE_EXISTING);
            PackManifest newManifest = PackManifest.capture(staging, packVersion);
            Path stagedManifest = staging.resolve(PackManifest.FILE_NAME);
            newManifest.write(stagedManifest);

            LoaderState loaderState = prepareLoader(
                    profile, minecraftVersion, loaderDependency, safeListener);
            updateProfile(profile, loaderState, safeProfileId, minecraftVersion,
                    packVersion, sourceProjectId, sourceVersionId);
            Path stagedProfile = staging.resolve(".ecl-profile-update.json");
            Files.writeString(stagedProfile, profile.toString(), StandardCharsets.UTF_8);

            int warnings = stagePackChanges(transaction, instanceRoot, staging,
                    oldManifest, newManifest, safeListener);
            transaction.stageReplacement(stagedManifest,
                    instanceRoot.resolve(PackManifest.FILE_NAME));
            transaction.stageReplacement(stagedProfile, profileFile);

            if (runningGuard.getAsBoolean()) {
                throw new IOException("Instance is running; modpack update was cancelled before commit");
            }
            transaction.commit();
            safeListener.onStatus(warnings == 0
                    ? "整合包更新完成: " + safeProfileId
                    : "整合包更新完成，已保留 " + warnings + " 个用户修改过的旧文件");
            return new InstallResult(safeProfileId,
                    JsonUtil.getString(profile, "eclModpackName", safeProfileId),
                    packVersion, minecraftVersion, loaderState.loaderId(),
                    instanceRoot, fileCount);
        }
    }

    private LoaderState prepareLoader(JsonObject profile, String minecraftVersion,
                                      LoaderDependency requested, Listener listener)
            throws IOException {
        if (requested == null) {
            return new LoaderState(minecraftVersion, "", "");
        }
        String currentMinecraft = JsonUtil.getString(profile, "eclMinecraftVersion", "");
        String currentLoader = JsonUtil.getString(profile, "eclModLoader", "");
        String currentLoaderVersion = JsonUtil.getString(profile, "eclModLoaderVersion", "");
        String currentParent = JsonUtil.getString(profile, "inheritsFrom", "");
        boolean changed = !minecraftVersion.equals(currentMinecraft)
                || !requested.loader().id().equalsIgnoreCase(currentLoader)
                || !requested.version().equals(currentLoaderVersion)
                || currentParent.isBlank();
        if (!changed) {
            return new LoaderState(currentParent, requested.loader().id(), requested.version());
        }
        listener.onStatus("正在准备整合包需要的 " + requested.loader().displayName()
                + " " + requested.version() + "...");
        ModLoaderInstaller.InstallResult installed = loaderInstallation.install(
                minecraftVersion, requested.loader(), requested.version(), listener);
        return new LoaderState(installed.profileId(), installed.loader().id(),
                installed.loaderVersion());
    }

    private static void updateProfile(JsonObject profile, LoaderState loaderState,
                                      String profileId, String minecraftVersion,
                                      String packVersion, String sourceProjectId,
                                      String sourceVersionId) {
        profile.addProperty("id", profileId);
        profile.addProperty("inheritsFrom", loaderState.parentProfile());
        profile.addProperty("eclMinecraftVersion", minecraftVersion);
        profile.addProperty("eclModLoader", loaderState.loaderId());
        profile.addProperty("eclModLoaderVersion", loaderState.loaderVersion());
        profile.addProperty("eclModpackVersion", packVersion);
        if (sourceProjectId != null && !sourceProjectId.isBlank()) {
            profile.addProperty("eclModpackProjectId", sourceProjectId.trim());
        }
        if (sourceVersionId != null && !sourceVersionId.isBlank()) {
            profile.addProperty("eclModpackVersionId", sourceVersionId.trim());
        }
        profile.addProperty("eclModpackSource", "modrinth");
    }

    private static int stagePackChanges(PackUpdateTransaction transaction, Path instanceRoot,
                                        Path staging, PackManifest oldManifest,
                                        PackManifest newManifest, Listener listener)
            throws IOException {
        for (String relative : newManifest.files().keySet()) {
            transaction.stageReplacement(
                    PackManifest.resolve(staging, relative),
                    PackManifest.resolve(instanceRoot, relative));
        }
        int warnings = 0;
        for (Map.Entry<String, String> old : oldManifest.files().entrySet()) {
            if (newManifest.files().containsKey(old.getKey())) {
                continue;
            }
            Path target = PackManifest.resolve(instanceRoot, old.getKey());
            if (!Files.exists(target)) {
                continue;
            }
            if (Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && PackManifest.sha512(target).equalsIgnoreCase(old.getValue())) {
                transaction.stageDeletion(target);
            } else {
                warnings++;
                listener.onStatus("警告：旧版文件已被用户修改，更新时予以保留: " + old.getKey());
            }
        }
        return warnings;
    }

    private static PackManifest readInstalledManifest(Path instanceRoot, Listener listener) {
        Path manifestFile = instanceRoot.resolve(PackManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            listener.onStatus("未找到旧整合包文件清单；本次更新不会删除旧版遗留文件");
            return new PackManifest("", Map.of());
        }
        try {
            return PackManifest.read(manifestFile);
        } catch (IOException error) {
            listener.onStatus("旧整合包文件清单无效；本次更新不会删除旧版遗留文件");
            return new PackManifest("", Map.of());
        }
    }

    private static int extractOverrides(ZipFile zip, String prefix, Path instanceRoot,
                                        ExtractionBudget budget)
            throws IOException {
        int extracted = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/');
            if (!name.startsWith(prefix) || name.equals(prefix)) {
                continue;
            }
            String relative = name.substring(prefix.length());
            Path destination = MrpackPathPolicy.safeResolve(instanceRoot, relative);
            if (entry.isDirectory()) {
                Files.createDirectories(destination);
                continue;
            }
            if (++budget.entries > MAX_OVERRIDE_ENTRIES) {
                throw new IOException("MRPACK override entry count exceeds the safety limit");
            }
            long declaredSize = entry.getSize();
            if (declaredSize > MAX_OVERRIDE_ENTRY_BYTES) {
                throw new IOException("整合包覆盖文件过大: " + relative);
            }
            if (declaredSize >= 0 && budget.total + declaredSize > MAX_TOTAL_OVERRIDE_BYTES) {
                throw new IOException("整合包覆盖文件总大小超过安全限制");
            }
            long compressedSize = entry.getCompressedSize();
            if (declaredSize > 0 && (compressedSize == 0 || (compressedSize > 0
                    && (double) declaredSize / compressedSize > MAX_OVERRIDE_COMPRESSION_RATIO))) {
                throw new IOException("MRPACK override compression ratio exceeds the safety limit: " + relative);
            }
            Files.createDirectories(destination.getParent());
            try (InputStream input = zip.getInputStream(entry);
                 var output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    written += read;
                    if (written > MAX_OVERRIDE_ENTRY_BYTES) {
                        throw new IOException("整合包覆盖文件解压后过大: " + relative);
                    }
                    output.write(buffer, 0, read);
                }
                budget.total += written;
                if (budget.total > MAX_TOTAL_OVERRIDE_BYTES) {
                    throw new IOException("整合包覆盖文件总大小超过安全限制");
                }
            }
            extracted++;
        }
        return extracted;
    }

    private static void writeProfile(String profileId, String parentProfile, String packName,
                                     String packVersion, String minecraftVersion,
                                     LoaderDependency loader, String sourceProjectId,
                                     String sourceVersionId) throws IOException {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", profileId);
        profile.addProperty("inheritsFrom", parentProfile);
        profile.addProperty("type", "release");
        profile.addProperty("eclMinecraftVersion", minecraftVersion);
        profile.addProperty("eclModLoader", loader == null ? "" : loader.loader().id());
        profile.addProperty("eclModLoaderVersion", loader == null ? "" : loader.version());
        profile.addProperty("eclModpackName", packName);
        profile.addProperty("eclModpackVersion", packVersion);
        if (sourceProjectId != null && !sourceProjectId.isBlank()) {
            profile.addProperty("eclModpackSource", "modrinth");
            profile.addProperty("eclModpackProjectId", sourceProjectId.trim());
        }
        if (sourceVersionId != null && !sourceVersionId.isBlank()) {
            profile.addProperty("eclModpackVersionId", sourceVersionId.trim());
        }
        Path profileDir = MrpackPathPolicy.profileDirectory(profileId);
        Files.createDirectories(profileDir);
        HttpUtil.writeJson(profileDir.resolve(profileId + ".json").toFile(), profile);
    }

    private record LoaderState(String parentProfile, String loaderId, String loaderVersion) {
    }

    private static final class ExtractionBudget {
        private long total;
        private int entries;
    }

}
