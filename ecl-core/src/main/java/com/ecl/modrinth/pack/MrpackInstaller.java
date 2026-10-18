package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.FileUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

/** Installs a Modrinth .mrpack as an isolated, launchable ECL profile. */
public final class MrpackInstaller {
    public interface Listener extends ModLoaderInstaller.Listener {
        default void onWarning(String message) {
            onStatus(message);
        }
    }

    public record InstallResult(String profileId, String name, String version,
                                String minecraftVersion, String loader, Path instanceDirectory,
                                int downloadedFiles) {
    }

    public enum FileChangeKind {
        ADDED, REPLACED, OVERWRITTEN_USER_FILE, DELETED, PRESERVED_USER_FILE
    }

    public record FileChange(String path, FileChangeKind kind) { }

    /** Downloaded and parsed pack contents kept outside the instance until final confirmation. */
    public record UpdatePreview(Path workingDirectory, String profileId,
                                String sourceProjectId, String sourceVersionId,
                                String minecraftVersion, String packVersion,
                                String loader, String loaderVersion, String parentProfile,
                                Path archive, Path stagedFiles, Path stagedVersions,
                                Path stagedLibraries, String initialProfileSha256,
                                Map<String, String> initialTargetSha512,
                                List<FileChange> changes, int downloadedFiles) implements AutoCloseable {
        public UpdatePreview {
            initialTargetSha512 = Map.copyOf(initialTargetSha512);
            changes = List.copyOf(changes);
        }

        @Override
        public void close() throws IOException {
            Path normalized = workingDirectory.toAbsolutePath().normalize();
            Path base = ECLConfig.getBaseDir().toPath().toAbsolutePath().normalize();
            if (!Objects.equals(normalized.getParent(), base)
                    || !normalized.getFileName().toString().startsWith(PREVIEW_DIRECTORY_PREFIX)
                    || normalized.startsWith(ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize())) {
                throw new IOException("Prepared modpack cleanup path is outside the temporary data root");
            }
            FileUtil.validateExistingAncestors(base, normalized);
            if (Files.exists(normalized) && !MrpackPathPolicy.deleteRecursively(normalized)) {
                throw new IOException("Unable to clean prepared modpack update files");
            }
        }
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

    /** Prefix of the temporary preview/update directories created next to the data root. */
    public static final String PREVIEW_DIRECTORY_PREFIX = "ecl-modpack-preview-";
    /** Default age after which an abandoned preview directory is considered stale. */
    public static final java.time.Duration STALE_PREVIEW_AGE = java.time.Duration.ofHours(24);

    /**
     * Removes preview directories left behind by a crashed or force-killed process.
     *
     * <p>Only direct children of the ECL data root whose names start with
     * {@link #PREVIEW_DIRECTORY_PREFIX} are considered, so managed instance and version trees are
     * never touched. Directories modified more recently than {@code maxAge} are skipped because a
     * running update may still own them.</p>
     *
     * @return the number of removed directories; {@code 0} when none were stale
     */
    public static int cleanStalePreviews(java.time.Duration maxAge) {
        java.time.Duration age = maxAge == null || maxAge.isNegative() ? STALE_PREVIEW_AGE : maxAge;
        Path base = ECLConfig.getBaseDir().toPath().toAbsolutePath().normalize();
        Path versions = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - age.toMillis();
        int removed = 0;
        try (var children = Files.list(base)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        || !child.getFileName().toString().startsWith(PREVIEW_DIRECTORY_PREFIX)
                        || child.startsWith(versions)) {
                    continue;
                }
                try {
                    FileUtil.validateExistingAncestors(base, child);
                    if (Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis() > cutoff) {
                        continue;
                    }
                    if (MrpackPathPolicy.deleteRecursively(child)) {
                        removed++;
                    }
                } catch (IOException | RuntimeException unsafeOrBusy) {
                    // A live preview or an unreadable entry is not our cleanup's problem.
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            return removed;
        }
        return removed;
    }

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

    /** Resolves and verifies all pack file changes in temporary storage without touching the instance. */
    public UpdatePreview prepareUpdate(File archive, File gameRoot, String profileId,
                                       String sourceProjectId, String sourceVersionId,
                                       Path workingDirectory, Listener listener) throws IOException {
        if (archive == null || !archive.isFile() || gameRoot == null) {
            throw new IOException("MRPACK update inputs are missing");
        }
        String safeProfileId = profileId == null ? "" : profileId.trim();
        FileUtil.requireSafeVersionId(safeProfileId);
        Path instanceRoot = MrpackPathPolicy.safeInstanceDirectory(gameRoot.toPath(), safeProfileId);
        Path profileFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), safeProfileId).toPath();
        if (!Files.isDirectory(instanceRoot) || !Files.isRegularFile(profileFile)) {
            throw new IOException("Modpack instance metadata is missing");
        }
        if (PackUpdateTransaction.hasIncompleteTransactions(instanceRoot)) {
            throw new IOException("An interrupted modpack transaction must be recovered before preview");
        }
        FileUtil.validateExistingAncestors(instanceRoot.getParent(), instanceRoot);
        FileUtil.validateExistingAncestors(ECLConfig.getVersionsDir().toPath(), profileFile);
        Listener safeListener = listener == null ? message -> { } : listener;
        JsonObject index = readIndex(archive);
        JsonObject dependencies = MrpackDependencyResolver.requireDependencies(
                index, "MRPACK index is missing dependencies");
        String minecraftVersion = MrpackDependencyResolver.requireMinecraftVersion(
                dependencies, "MRPACK does not declare a Minecraft version");
        LoaderDependency requestedLoader = MrpackDependencyResolver.findLoader(dependencies);
        String packVersion = JsonUtil.getString(index, "versionId", sourceVersionId);
        JsonObject currentProfile = HttpUtil.readJson(profileFile.toFile());
        String currentMinecraft = JsonUtil.getString(currentProfile, "eclMinecraftVersion",
                JsonUtil.getString(currentProfile, "inheritsFrom", ""));
        if (!currentMinecraft.equals(minecraftVersion)) {
            throw new IOException("Modpack update cannot change the Minecraft version");
        }
        String currentLoader = JsonUtil.getString(currentProfile, "eclModLoader", "");
        String requestedLoaderId = requestedLoader == null ? "" : requestedLoader.loader().id();
        if (!currentLoader.equalsIgnoreCase(requestedLoaderId)) {
            throw new IOException("Modpack update cannot change the loader type");
        }
        Path oldManifestPath = PackManifest.resolve(instanceRoot, PackManifest.FILE_NAME);
        if (!Files.isRegularFile(oldManifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The installed pack manifest is missing; update preview cannot guarantee rollback");
        }
        PackManifest oldManifest = PackManifest.read(oldManifestPath);
        for (String path : oldManifest.files().keySet()) validateUpdateScope(path);
        Path work = workingDirectory.toAbsolutePath().normalize();
        Path dataRoot = ECLConfig.getBaseDir().toPath().toAbsolutePath().normalize();
        if (!Objects.equals(work.getParent(), dataRoot)
                || !work.getFileName().toString().startsWith(PREVIEW_DIRECTORY_PREFIX)
                || work.startsWith(instanceRoot)
                || work.startsWith(ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize())) {
            throw new IOException("Modpack preview files must be outside managed instance and version roots");
        }
        FileUtil.validateExistingAncestors(dataRoot, work);
        Files.createDirectories(work);
        Path stagedFiles = Files.createDirectory(work.resolve("files"));
        int fileCount = installContents(archive, stagedFiles, safeListener);
        Path stagedArchive = stagedFiles.resolve(safeProfileId + ".mrpack");
        Files.copy(archive.toPath(), stagedArchive, StandardCopyOption.REPLACE_EXISTING);
        PackManifest newManifest = PackManifest.capture(stagedFiles, packVersion);
        for (String path : newManifest.files().keySet()) validateUpdateScope(path);
        newManifest.write(stagedFiles.resolve(PackManifest.FILE_NAME));

        Path stagedVersions = Files.createDirectory(work.resolve("versions"));
        Path stagedLibraries = Files.createDirectory(work.resolve("libraries"));
        MrpackInstallSupport.LoaderState loader = prepareLoaderPreview(currentProfile, minecraftVersion, requestedLoader,
                safeListener, stagedVersions, stagedLibraries);
        List<FileChange> changes = describePackChanges(instanceRoot, oldManifest, newManifest);
        Map<String, String> initialHashes = capturePackTargetHashes(instanceRoot, oldManifest, newManifest);
        return new UpdatePreview(work, safeProfileId,
                sourceProjectId == null ? "" : sourceProjectId,
                sourceVersionId == null ? "" : sourceVersionId,
                minecraftVersion, packVersion, loader.loaderId(), loader.loaderVersion(),
                loader.parentProfile(), stagedArchive, stagedFiles, stagedVersions, stagedLibraries,
                sha256(profileFile), initialHashes, changes, fileCount);
    }

    private JsonObject readIndex(File archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            JsonObject index = MrpackIndexReader.read(zip,
                    "MRPACK index is missing or exceeds the safety limit",
                    "MRPACK index is missing or exceeds the safety limit",
                    "MRPACK index is invalid");
            if (!"1".equals(JsonUtil.getString(index, "formatVersion", ""))) {
                throw new IOException("Unsupported MRPACK format version");
            }
            return index;
        }
    }

    private static void validateUpdateScope(String relative) throws IOException {
        FileUtil.safeArchiveEntry(Path.of(".").toAbsolutePath().normalize(), relative);
        String first = Path.of(relative).getName(0).toString().toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("saves", "screenshots", "logs", "crash-reports", ".ecl",
                "versions", "libraries", "assets", "servers.dat", "usercache.json")
                .contains(first)) {
            throw new IOException("Modpack update references user or shared data outside its rollback scope: "
                    + relative);
        }
    }

    private MrpackInstallSupport.LoaderState prepareLoaderPreview(
            JsonObject profile, String minecraftVersion, LoaderDependency requested,
            Listener listener, Path stagedVersions, Path stagedLibraries) throws IOException {
        if (requested == null) return new MrpackInstallSupport.LoaderState(minecraftVersion, "", "");
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
        listener.onStatus("正在解析整合包需要的 " + requested.loader().displayName()
                + " " + requested.version() + "...");
        ModLoaderInstaller.InstallResult prepared = stagedLoaderInstallation.install(
                stagedVersions, stagedLibraries, minecraftVersion, requested.loader(),
                requested.version(), listener);
        return new MrpackInstallSupport.LoaderState(
                prepared.profileId(), prepared.loader().id(), prepared.loaderVersion());
    }

    private static List<FileChange> describePackChanges(Path instanceRoot,
                                                        PackManifest oldManifest,
                                                        PackManifest newManifest) throws IOException {
        List<FileChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> file : newManifest.files().entrySet()) {
            String oldHash = oldManifest.files().get(file.getKey());
            Path target = PackManifest.resolve(instanceRoot, file.getKey());
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pack update target is not a regular file: " + file.getKey());
            }
            boolean userModified = exists && oldHash != null
                    && !PackManifest.sha512(target).equalsIgnoreCase(oldHash);
            if (oldHash == null) {
                changes.add(new FileChange(file.getKey(), exists
                        ? FileChangeKind.OVERWRITTEN_USER_FILE : FileChangeKind.ADDED));
            } else if (userModified) {
                changes.add(new FileChange(file.getKey(), FileChangeKind.OVERWRITTEN_USER_FILE));
            } else if (!oldHash.equalsIgnoreCase(file.getValue())) {
                changes.add(new FileChange(file.getKey(), FileChangeKind.REPLACED));
            }
        }
        for (Map.Entry<String, String> old : oldManifest.files().entrySet()) {
            if (newManifest.files().containsKey(old.getKey())) continue;
            Path target = PackManifest.resolve(instanceRoot, old.getKey());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && PackManifest.sha512(target).equalsIgnoreCase(old.getValue())) {
                changes.add(new FileChange(old.getKey(), FileChangeKind.DELETED));
            } else {
                changes.add(new FileChange(old.getKey(), FileChangeKind.PRESERVED_USER_FILE));
            }
        }
        return List.copyOf(changes);
    }

    private static Map<String, String> capturePackTargetHashes(Path instanceRoot,
                                                               PackManifest oldManifest,
                                                               PackManifest newManifest) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        java.util.Set<String> paths = new java.util.TreeSet<>();
        paths.addAll(oldManifest.files().keySet());
        paths.addAll(newManifest.files().keySet());
        paths.add(PackManifest.FILE_NAME);
        for (String relative : paths) {
            Path target = PackManifest.resolve(instanceRoot, relative);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                hashes.put(relative, "");
            } else if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                hashes.put(relative, PackManifest.sha512(target));
            } else {
                throw new IOException("Pack update target is not a regular file: " + relative);
            }
        }
        return Map.copyOf(hashes);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void copyFiles(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                if (Files.isSymbolicLink(source)) {
                    throw new IOException("Prepared pack contains a symbolic link");
                }
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                Path relative = sourceRoot.relativize(source).normalize();
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot)) throw new IOException("Prepared pack path escapes staging");
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void requirePreparedFresh(UpdatePreview prepared, Path instanceRoot,
                                      Path profileFile) throws IOException {
        if (!sha256(profileFile).equals(prepared.initialProfileSha256())) {
            throw new IOException("Modpack profile changed after preview; inspect the update again");
        }
        for (Map.Entry<String, String> expected : prepared.initialTargetSha512().entrySet()) {
            Path target = PackManifest.resolve(instanceRoot, expected.getKey());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!expected.getValue().isBlank()) {
                    throw new IOException("Pack file changed after preview: " + expected.getKey());
                }
            } else if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !PackManifest.sha512(target).equalsIgnoreCase(expected.getValue())) {
                throw new IOException("Pack file changed after preview: " + expected.getKey());
            }
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

    /** Applies the archive and file plan that the user reviewed before any instance files changed. */
    public InstallResult applyPreparedUpdate(UpdatePreview prepared, File gameRoot,
                                             BooleanSupplier instanceRunning,
                                             Listener listener) throws IOException {
        java.util.Objects.requireNonNull(prepared, "prepared");
        String profileId = prepared.profileId();
        FileUtil.requireSafeVersionId(profileId);
        if (!prepared.archive().startsWith(prepared.workingDirectory())
                || !Files.isRegularFile(prepared.archive())
                || !prepared.stagedFiles().startsWith(prepared.workingDirectory())
                || !prepared.stagedVersions().startsWith(prepared.workingDirectory())
                || !prepared.stagedLibraries().startsWith(prepared.workingDirectory())) {
            throw new IOException("Prepared modpack update files are missing or unsafe");
        }
        Path dataRoot = ECLConfig.getBaseDir().toPath().toAbsolutePath().normalize();
        FileUtil.validateExistingAncestors(dataRoot, prepared.workingDirectory());
        FileUtil.validateExistingAncestors(prepared.workingDirectory(), prepared.archive());
        FileUtil.validateExistingAncestors(prepared.workingDirectory(), prepared.stagedFiles());
        FileUtil.validateExistingAncestors(prepared.workingDirectory(), prepared.stagedVersions());
        FileUtil.validateExistingAncestors(prepared.workingDirectory(), prepared.stagedLibraries());
        Path gameRootPath = java.util.Objects.requireNonNull(gameRoot, "gameRoot")
                .toPath().toAbsolutePath().normalize();
        Path instanceRoot = MrpackPathPolicy.safeInstanceDirectory(gameRootPath, profileId);
        Path profileFile = FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), profileId)
                .toPath().toAbsolutePath().normalize();
        if (PackUpdateTransaction.hasIncompleteTransactions(instanceRoot)) {
            throw new IOException("An interrupted modpack transaction must be recovered before update");
        }
        FileUtil.validateExistingAncestors(instanceRoot.getParent(), instanceRoot);
        FileUtil.validateExistingAncestors(ECLConfig.getVersionsDir().toPath(), profileFile);
        requirePreparedFresh(prepared, instanceRoot, profileFile);
        Listener safeListener = listener == null ? message -> { } : listener;
        BooleanSupplier runningGuard = instanceRunning == null ? () -> false : instanceRunning;
        JsonObject profile = HttpUtil.readJson(profileFile.toFile());
        PackManifest oldManifest = PackManifest.read(PackManifest.resolve(instanceRoot, PackManifest.FILE_NAME));

        try (PackUpdateTransaction transaction = transactionFactory.create(instanceRoot, profileFile)) {
            Path staging = transaction.stagingDirectory();
            copyFiles(prepared.stagedFiles(), staging);
            Path loaderStaging = staging.resolve(".ecl-loader");
            Path stagedVersions = loaderStaging.resolve("versions");
            Path stagedLibraries = loaderStaging.resolve("libraries");
            copyFiles(prepared.stagedVersions(), stagedVersions);
            copyFiles(prepared.stagedLibraries(), stagedLibraries);
            transaction.stageExternalDirectory(stagedVersions, ECLConfig.getVersionsDir().toPath());
            transaction.stageExternalDirectory(stagedLibraries, ECLConfig.getLibrariesDir().toPath());

            Path stagedManifest = staging.resolve(PackManifest.FILE_NAME);
            PackManifest newManifest = PackManifest.read(stagedManifest);
            if (!prepared.packVersion().equals(newManifest.packVersion())) {
                throw new IOException("Prepared modpack manifest does not match its preview");
            }
            MrpackProfileWriter.update(profile, profileId, prepared.parentProfile(),
                    prepared.minecraftVersion(), prepared.packVersion(), prepared.loader(),
                    prepared.loaderVersion(), prepared.sourceProjectId(), prepared.sourceVersionId());
            Path stagedProfile = staging.resolve(".ecl-profile-update.json");
            Files.writeString(stagedProfile, profile.toString(), StandardCharsets.UTF_8);
            int warnings = MrpackInstallSupport.stagePackChanges(transaction, instanceRoot, staging,
                    oldManifest, newManifest, safeListener);
            transaction.stageReplacement(stagedManifest, instanceRoot.resolve(PackManifest.FILE_NAME));
            transaction.stageReplacement(stagedProfile, profileFile);
            if (runningGuard.getAsBoolean()) {
                throw new IOException("Instance is running; update was cancelled before commit");
            }
            requirePreparedFresh(prepared, instanceRoot, profileFile);
            transaction.commit();
            safeListener.onStatus(warnings == 0
                    ? "整合包更新完成: " + profileId
                    : "整合包更新完成，已保留 " + warnings + " 个用户修改过的旧文件");
            return new InstallResult(profileId,
                    JsonUtil.getString(profile, "eclModpackName", profileId),
                    prepared.packVersion(), prepared.minecraftVersion(), prepared.loader(),
                    instanceRoot, prepared.downloadedFiles());
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
