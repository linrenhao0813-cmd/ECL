package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.TextUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs a Modrinth .mrpack as an isolated, launchable ECL profile. */
public final class MrpackInstaller {
    private static final long MAX_OVERRIDE_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_OVERRIDE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_OVERRIDE_ENTRIES = 100_000;
    private static final double MAX_OVERRIDE_COMPRESSION_RATIO = 200.0;
    private static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    private static final long MAX_INDEXED_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_INDEXED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_INDEXED_FILES = 100_000;

    public interface Listener extends ModLoaderInstaller.Listener {
    }

    public record InstallResult(String profileId, String name, String version,
                                String minecraftVersion, String loader, Path instanceDirectory,
                                int downloadedFiles) {
    }

    private final ModLoaderInstaller loaderInstaller;

    public MrpackInstaller() {
        this(new ModLoaderInstaller());
    }

    MrpackInstaller(ModLoaderInstaller loaderInstaller) {
        this.loaderInstaller = loaderInstaller;
    }

    /** Install the client files and overrides from an MRPACK into an existing staging directory. */
    public int installContents(File archive, Path instanceRoot, Listener listener) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("MRPACK file does not exist");
        Path root = instanceRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Listener safeListener = listener == null ? message -> { } : listener;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            JsonObject index = readIndex(zip);
            if (!"1".equals(JsonUtil.getString(index, "formatVersion", ""))) {
                throw new IOException("Unsupported MRPACK format version");
            }
            JsonObject dependencies = requireObject(index, "dependencies");
            if (JsonUtil.getString(dependencies, "minecraft", "").isBlank()) {
                throw new IOException("MRPACK does not declare a Minecraft version");
            }
            int installed = installIndexedFiles(index, root, safeListener);
            ExtractionBudget budget = new ExtractionBudget();
            installed += extractOverrides(zip, "overrides/", root, budget);
            installed += extractOverrides(zip, "client-overrides/", root, budget);
            return installed;
        }
    }

    private static JsonObject readIndex(ZipFile zip) throws IOException {
        ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
        if (indexEntry == null || indexEntry.getSize() > MAX_INDEX_BYTES) {
            throw new IOException("MRPACK index is missing or exceeds the safety limit");
        }
        try (InputStream input = zip.getInputStream(indexEntry)) {
            byte[] bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) {
                throw new IOException("MRPACK index exceeds the safety limit");
            }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("MRPACK index is invalid", failure);
        }
    }

    public InstallResult install(File archive, File gameRoot, String preferredName,
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
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) {
                throw new IOException("该文件不是有效的 .mrpack：缺少 modrinth.index.json");
            }
            if (indexEntry.getSize() > MAX_INDEX_BYTES) {
                throw new IOException("整合包索引超过安全限制");
            }
            try (InputStream input = zip.getInputStream(indexEntry)) {
                byte[] bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
                if (bytes.length > MAX_INDEX_BYTES) {
                    throw new IOException("整合包索引超过安全限制");
                }
                index = JsonParser.parseString(
                        new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("整合包索引格式无效", e);
            }

            String formatVersion = JsonUtil.getString(index, "formatVersion", "");
            if (!"1".equals(formatVersion)) {
                throw new IOException("暂不支持的 Modrinth 整合包格式版本: " + formatVersion);
            }
            JsonObject dependencies = requireObject(index, "dependencies");
            String minecraftVersion = JsonUtil.getString(dependencies, "minecraft", "");
            if (minecraftVersion.isBlank()) {
                throw new IOException("整合包没有声明 Minecraft 版本");
            }
            LoaderDependency loaderDependency = findLoader(dependencies);
            String parentProfile = minecraftVersion;
            if (loaderDependency != null) {
                safeListener.onStatus("正在准备整合包需要的 " + loaderDependency.loader.displayName()
                        + " " + loaderDependency.version + "...");
                parentProfile = loaderInstaller.install(minecraftVersion, loaderDependency.loader,
                        loaderDependency.version, safeListener).profileId();
            }

            String name = preferredName == null || preferredName.isBlank()
                    ? JsonUtil.getString(index, "name", "Modrinth Pack") : preferredName.trim();
            String version = JsonUtil.getString(index, "versionId", "1");
            String profileId = uniqueProfileId(name, version, gameRoot.toPath());
            Path instanceRoot = safeInstanceDirectory(gameRoot.toPath(), profileId);
            Files.createDirectories(instanceRoot.getParent());
            Path staging = Files.createTempDirectory(instanceRoot.getParent(),
                    ".ecl-pack-install-" + profileId + "-");
            try {
                safeListener.onStatus("正在安装整合包文件...");
                int fileCount = installIndexedFiles(index, staging, safeListener);
                ExtractionBudget extractionBudget = new ExtractionBudget();
                extractOverrides(zip, "overrides/", staging, extractionBudget);
                extractOverrides(zip, "client-overrides/", staging, extractionBudget);
                Files.copy(archive.toPath(), staging.resolve(profileId + ".mrpack"),
                        StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(staging, instanceRoot, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveError) {
                    Files.move(staging, instanceRoot);
                }
                try {
                    writeProfile(profileId, parentProfile, name, version,
                            minecraftVersion, loaderDependency);
                } catch (IOException profileError) {
                    deleteRecursively(instanceRoot);
                    deleteProfile(profileId);
                    throw profileError;
                }
                safeListener.onStatus("整合包已安装为独立实例 " + profileId);
                return new InstallResult(profileId, name, version, minecraftVersion,
                        loaderDependency == null ? "" : loaderDependency.loader.id(),
                        instanceRoot, fileCount);
            } finally {
                deleteRecursively(staging);
            }
        }
    }

    private int installIndexedFiles(JsonObject index, Path instanceRoot, Listener listener)
            throws IOException {
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
            Path destination = safeResolve(instanceRoot, relative);
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
                    String url = download.getAsString();
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
                        throw new IOException("MRPACK file size does not match its manifest: " + relative);
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
            Path destination = safeResolve(instanceRoot, relative);
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
                                     LoaderDependency loader) throws IOException {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", profileId);
        profile.addProperty("inheritsFrom", parentProfile);
        profile.addProperty("type", "release");
        profile.addProperty("eclMinecraftVersion", minecraftVersion);
        profile.addProperty("eclModLoader", loader == null ? "" : loader.loader.id());
        profile.addProperty("eclModLoaderVersion", loader == null ? "" : loader.version);
        profile.addProperty("eclModpackName", packName);
        profile.addProperty("eclModpackVersion", packVersion);
        Path root = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        Path profileDir = root.resolve(profileId).normalize();
        if (!profileDir.startsWith(root)) {
            throw new IOException("整合包版本目录越界");
        }
        Files.createDirectories(profileDir);
        HttpUtil.writeJson(profileDir.resolve(profileId + ".json").toFile(), profile);
    }

    private static LoaderDependency findLoader(JsonObject dependencies) throws IOException {
        LoaderDependency found = null;
        for (var candidate : new Object[][]{
                {"fabric-loader", ModLoaderInstaller.Loader.FABRIC},
                {"quilt-loader", ModLoaderInstaller.Loader.QUILT},
                {"forge", ModLoaderInstaller.Loader.FORGE},
                {"neoforge", ModLoaderInstaller.Loader.NEOFORGE}}) {
            String key = (String) candidate[0];
            if (!dependencies.has(key)) continue;
            if (found != null) {
                throw new IOException("整合包同时声明了多个模组加载器");
            }
            found = new LoaderDependency((ModLoaderInstaller.Loader) candidate[1],
                    dependencies.get(key).getAsString());
        }
        return found;
    }

    private static JsonObject requireObject(JsonObject parent, String key) throws IOException {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IOException("整合包索引缺少 " + key);
        }
        return parent.getAsJsonObject(key);
    }

    private static String uniqueProfileId(String name, String version, Path gameRoot) {
        String raw = (name + "-" + version).trim();
        String base = TextUtil.replaceInvalidFilenameChars(raw)
                .replace(' ', '-').replaceAll("-+", "-")
                .replaceAll("[. ]+$", "");
        if (base.isBlank() || isWindowsReservedName(base)) base = "modrinth-pack";
        if (base.length() > 72) {
            String hash = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8))
                    .toString().substring(0, 8);
            base = base.substring(0, 63).replaceAll("[. ]+$", "") + "-" + hash;
        }
        Path versions = ECLConfig.getVersionsDir().toPath();
        Path gameVersions = gameRoot.resolve("versions");
        String candidate = base;
        int suffix = 2;
        while (Files.exists(versions.resolve(candidate)) || Files.exists(gameVersions.resolve(candidate))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static boolean isWindowsReservedName(String value) {
        String name = value.replaceFirst("\\..*$", "").toUpperCase(Locale.ROOT);
        return name.equals("CON") || name.equals("PRN") || name.equals("AUX")
                || name.equals("NUL") || name.matches("COM[1-9]") || name.matches("LPT[1-9]");
    }

    private static void deleteProfile(String profileId) {
        Path root = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(profileId).normalize();
        if (target.startsWith(root) && !target.equals(root)) {
            deleteRecursively(target);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path safeInstanceDirectory(Path gameRoot, String profileId) throws IOException {
        Path root = gameRoot.toAbsolutePath().normalize().resolve("versions").normalize();
        Path result = root.resolve(profileId).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("整合包实例目录越界");
        }
        return result;
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("整合包包含空文件路径");
        }
        String normalizedRelative = relative.replace('\\', '/');
        if (normalizedRelative.startsWith("/") || normalizedRelative.matches("^[A-Za-z]:.*")) {
            throw new IOException("整合包包含绝对路径: " + relative);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(normalizedRelative).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("整合包包含越界路径: " + relative);
        }
        return result;
    }

    private record LoaderDependency(ModLoaderInstaller.Loader loader, String version) {
    }

    private static final class ExtractionBudget {
        private long total;
        private int entries;
    }
}
