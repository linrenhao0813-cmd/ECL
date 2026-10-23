package com.ecl.download;

import com.ecl.game.AssetIndex;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Materializes legacy asset indexes that declare {@code virtual} or {@code map_to_resources}.
 *
 * <p>Versions such as 1.7.2 read sounds from {@code assets/virtual/<index>/<name>}, and versions
 * before the objects layout read them from {@code <gameDir>/resources/<name>}. Both layouts refer
 * to the very same payloads as {@code assets/objects/<xx>/<hash>}, so the object file is hard
 * linked when the filesystem allows it and copied otherwise.</p>
 */
public final class LegacyAssetMaterializer {
    private static final Pattern SHA1 = Pattern.compile("(?i)[0-9a-f]{40}");

    private LegacyAssetMaterializer() {
    }

    /** Rebuilds legacy views for the actual run directory, including already-installed versions. */
    public static void prepare(AssetIndex index, File assetsDirectory, File gameDirectory) throws IOException {
        JsonObject contents = readIndex(index, assetsDirectory);
        if (contents == null) return;
        Path assets = assetsDirectory.toPath().toAbsolutePath().normalize();
        JsonObject objects = contents.getAsJsonObject("objects");
        if (isVirtual(contents)) {
            Path virtual = assets.resolve("virtual").resolve(index.id());
            FileUtil.validateExistingAncestors(assets, virtual);
            materialize(objects, assets.resolve("objects"), virtual);
        }
        if (mapsToResources(contents)) {
            Path game = gameDirectory.toPath().toAbsolutePath().normalize();
            Path resources = game.resolve("resources");
            FileUtil.validateExistingAncestors(game, resources);
            materialize(objects, assets.resolve("objects"), resources);
        }
    }

    /** Resolves the legacy argument from index contents, never from the version's index descriptor. */
    public static File gameAssetsDirectory(AssetIndex index, File assetsDirectory, File gameDirectory) throws IOException {
        JsonObject contents = readIndex(index, assetsDirectory);
        if (isVirtual(contents)) return new File(assetsDirectory, "virtual/" + index.id());
        return mapsToResources(contents) ? new File(gameDirectory, "resources") : assetsDirectory;
    }

    private static JsonObject readIndex(AssetIndex index, File assetsDirectory) throws IOException {
        if (index == null || !index.hasId()) return null;
        FileUtil.requireSafeVersionId(index.id());
        File file = FileUtil.safeResolveUnder(assetsDirectory, "indexes/" + index.id() + ".json");
        FileUtil.validateExistingAncestors(assetsDirectory.toPath(), file.toPath());
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        if (index.hasChecksum() && !FileUtil.verifySha1(file, index.sha1())) {
            throw new IOException("资源索引校验失败: " + index.id());
        }
        try {
            return HttpUtil.readJson(file);
        } catch (RuntimeException invalid) {
            throw new IOException("资源索引无效: " + index.id(), invalid);
        }
    }

    static boolean isVirtual(JsonObject indexJson) {
        return flag(indexJson, "virtual");
    }

    static boolean mapsToResources(JsonObject indexJson) {
        return flag(indexJson, "map_to_resources") || flag(indexJson, "mapToResources");
    }

    /**
     * Mirrors every {@code objects} entry as {@code <destinationRoot>/<name>}.
     *
     * @param objects asset index {@code objects} object, keyed by asset name
     * @param objectsRoot the {@code assets/objects} directory holding payloads
     * @param destinationRoot the materialization root ({@code assets/virtual/<id>} or
     *                        {@code <gameDir>/resources}) that must contain every target
     */
    static void materialize(JsonObject objects, Path objectsRoot, Path destinationRoot)
            throws IOException {
        if (objects == null) {
            return;
        }
        Path root = destinationRoot.toAbsolutePath().normalize();
        for (String name : objects.keySet()) {
            JsonObject entry = objects.get(name) != null && objects.get(name).isJsonObject()
                    ? objects.getAsJsonObject(name) : null;
            if (entry == null) {
                throw new IOException("资源索引条目无效: " + name);
            }
            Path source = objectFile(objectsRoot, requireHash(entry, name));
            FileUtil.validateExistingAncestors(objectsRoot, source);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("资源对象缺失: " + name);
            }
            materializeOne(source, root, name);
        }
    }

    private static void materializeOne(Path source, Path destinationRoot, String name)
            throws IOException {
        Path destination = FileUtil.safeArchiveEntry(destinationRoot, name);
        FileUtil.validateExistingAncestors(destinationRoot, destination);
        if (isCurrentMaterialization(destination, source)) {
            return;
        }
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("旧版资源目标路径无效: " + name);
        }
        Files.createDirectories(parent);
        FileUtil.validateExistingAncestors(destinationRoot, destination);
        Path temporary = parent.resolve(".ecl-asset-" + UUID.randomUUID() + ".tmp");
        try {
            linkOrCopy(source, temporary);
            FileUtil.moveReplacing(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isCurrentMaterialization(Path destination, Path source) throws IOException {
        return Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                && !FileUtil.isSymlinkOrReparsePoint(destination)
                && Files.size(destination) == Files.size(source)
                && (Files.isSameFile(destination, source) || Files.mismatch(destination, source) == -1);
    }

    private static void linkOrCopy(Path source, Path destination) throws IOException {
        try {
            Files.createLink(destination, source);
            return;
        } catch (IOException | UnsupportedOperationException | SecurityException unsupportedLink) {
            // Different volumes, a non-NTFS filesystem or a restrictive ACL: copy instead.
        }
        Files.copy(source, destination);
    }

    private static Path objectFile(Path objectsRoot, String hash) throws IOException {
        return FileUtil.safeResolveUnder(objectsRoot.toFile(),
                hash.substring(0, 2) + "/" + hash).toPath();
    }

    private static String requireHash(JsonObject entry, String name) throws IOException {
        String hash = JsonUtil.getString(entry, "hash", "");
        if (!SHA1.matcher(hash).matches()) {
            throw new IOException("资源对象哈希无效: " + name);
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private static boolean flag(JsonObject indexJson, String key) {
        if (indexJson == null || !indexJson.has(key) || !indexJson.get(key).isJsonPrimitive()) {
            return false;
        }
        try {
            return indexJson.get(key).getAsBoolean();
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
