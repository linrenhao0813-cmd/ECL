package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

/** Installs a Modrinth .mrpack as an isolated, launchable ECL profile. */
public final class MrpackInstaller {
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
    interface StagedLoaderInstallation {
        ModLoaderInstaller.InstallResult install(
                Path versionsDirectory,
                Path librariesDirectory,
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
    private final StagedLoaderInstallation stagedLoaderInstallation;
    private final TransactionFactory transactionFactory;
    private final java.util.Set<String> trustedDownloadHosts;

    public MrpackInstaller() {
        this(new ModLoaderInstaller()::install,
                (versions, libraries, minecraft, loader, version, listener) ->
                        new ModLoaderInstaller(versions, libraries).install(
                                minecraft, loader, version, listener),
                PackUpdateTransaction::new,
                MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS);
    }

    public MrpackInstaller(java.util.Set<String> trustedDownloadHosts) {
        this(new ModLoaderInstaller()::install,
                (versions, libraries, minecraft, loader, version, listener) ->
                        new ModLoaderInstaller(versions, libraries).install(
                                minecraft, loader, version, listener),
                PackUpdateTransaction::new,
                trustedDownloadHosts);
    }

    MrpackInstaller(ModLoaderInstaller loaderInstaller) {
        this(loaderInstaller::install,
                (versions, libraries, minecraft, loader, version, listener) ->
                        new ModLoaderInstaller(versions, libraries).install(
                                minecraft, loader, version, listener),
                PackUpdateTransaction::new,
                MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS);
    }

    MrpackInstaller(LoaderInstallation loaderInstallation, TransactionFactory transactionFactory) {
        this(loaderInstallation,
                (versions, libraries, minecraft, loader, version, listener) ->
                        loaderInstallation.install(minecraft, loader, version, listener),
                transactionFactory, MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS);
    }

    MrpackInstaller(StagedLoaderInstallation stagedLoaderInstallation,
                    TransactionFactory transactionFactory) {
        this((minecraft, loader, version, listener) ->
                        new ModLoaderInstaller().install(minecraft, loader, version, listener),
                stagedLoaderInstallation, transactionFactory,
                MrpackFileInstaller.DEFAULT_TRUSTED_DOWNLOAD_HOSTS);
    }

    private MrpackInstaller(LoaderInstallation loaderInstallation,
                            StagedLoaderInstallation stagedLoaderInstallation,
                            TransactionFactory transactionFactory,
                            java.util.Set<String> trustedDownloadHosts) {
        this.loaderInstallation = java.util.Objects.requireNonNull(
                loaderInstallation, "loaderInstallation");
        this.stagedLoaderInstallation = java.util.Objects.requireNonNull(
                stagedLoaderInstallation, "stagedLoaderInstallation");
        this.transactionFactory = java.util.Objects.requireNonNull(
                transactionFactory, "transactionFactory");
        this.trustedDownloadHosts = java.util.Set.copyOf(
                java.util.Objects.requireNonNull(trustedDownloadHosts, "trustedDownloadHosts"));
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
            int installed = MrpackFileInstaller.installIndexedFiles(
                    index, root, safeListener, trustedDownloadHosts);
            MrpackOverrideExtractor.ExtractionBudget budget =
                    new MrpackOverrideExtractor.ExtractionBudget();
            installed += MrpackOverrideExtractor.extract(zip, "overrides/", root, budget);
            installed += MrpackOverrideExtractor.extract(zip, "client-overrides/", root, budget);
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
                int fileCount = MrpackFileInstaller.installIndexedFiles(
                        index, staging, safeListener, trustedDownloadHosts);
                MrpackOverrideExtractor.ExtractionBudget extractionBudget =
                        new MrpackOverrideExtractor.ExtractionBudget();
                MrpackOverrideExtractor.extract(zip, "overrides/", staging, extractionBudget);
                MrpackOverrideExtractor.extract(zip, "client-overrides/", staging, extractionBudget);
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
                    MrpackProfileWriter.write(
                            profileId, parentProfile, name, version, minecraftVersion,
                            loaderDependency == null ? "" : loaderDependency.loader().id(),
                            loaderDependency == null ? "" : loaderDependency.version(),
                            sourceProjectId, sourceVersionId);
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
        // Recover interrupted writes before reading either profile or installed manifest; those
        // reads must observe the post-recovery state rather than stale half-applied metadata.
        PackUpdateTransaction.recoverIncompleteTransactions(instanceRoot, profileFile);
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
        PackManifest oldManifest = MrpackInstallSupport.readInstalledManifest(instanceRoot, safeListener);

        try (PackUpdateTransaction transaction = transactionFactory.create(instanceRoot, profileFile)) {
            Path staging = transaction.stagingDirectory();
            safeListener.onStatus("正在准备整合包更新文件...");
            int fileCount = installContents(archive, staging, safeListener);
            Files.copy(archive.toPath(), staging.resolve(safeProfileId + ".mrpack"),
                    StandardCopyOption.REPLACE_EXISTING);
            PackManifest newManifest = PackManifest.capture(staging, packVersion);
            Path stagedManifest = staging.resolve(PackManifest.FILE_NAME);
            newManifest.write(stagedManifest);

            Path loaderStaging = staging.resolve(".ecl-loader");
            MrpackInstallSupport.LoaderState loaderState = prepareLoader(
                    profile, minecraftVersion, loaderDependency, safeListener,
                    loaderStaging.resolve("versions"), loaderStaging.resolve("libraries"), transaction);
            MrpackProfileWriter.update(
                    profile, safeProfileId, loaderState.parentProfile(), minecraftVersion,
                    packVersion, loaderState.loaderId(), loaderState.loaderVersion(),
                    sourceProjectId, sourceVersionId);
            Path stagedProfile = staging.resolve(".ecl-profile-update.json");
            Files.writeString(stagedProfile, profile.toString(), StandardCharsets.UTF_8);

            int warnings = MrpackInstallSupport.stagePackChanges(transaction, instanceRoot, staging,
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

    private MrpackInstallSupport.LoaderState prepareLoader(JsonObject profile, String minecraftVersion,
                                                           LoaderDependency requested, Listener listener,
                                                           Path stagedVersions, Path stagedLibraries,
                                                           PackUpdateTransaction transaction)
            throws IOException {
        if (requested == null) {
            return new MrpackInstallSupport.LoaderState(minecraftVersion, "", "");
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
            return new MrpackInstallSupport.LoaderState(
                    currentParent, requested.loader().id(), requested.version());
        }
        listener.onStatus("正在准备整合包需要的 " + requested.loader().displayName()
                + " " + requested.version() + "...");
        ModLoaderInstaller.InstallResult installed = stagedLoaderInstallation.install(
                stagedVersions, stagedLibraries, minecraftVersion, requested.loader(),
                requested.version(), listener);
        transaction.stageExternalDirectory(stagedVersions,
                ECLConfig.getVersionsDir().toPath());
        transaction.stageExternalDirectory(stagedLibraries,
                ECLConfig.getLibrariesDir().toPath());
        return new MrpackInstallSupport.LoaderState(
                installed.profileId(), installed.loader().id(), installed.loaderVersion());
    }
}
