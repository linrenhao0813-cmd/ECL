package com.ecl.game;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for locally stored Minecraft version metadata.
 *
 * <p>Owns two responsibilities that were previously scattered across the launcher: resolving the
 * {@code inheritsFrom} inheritance chain into one effective JSON document, and interpreting that
 * document as typed {@link VersionMetadata}. The merge rules mirror exactly what the game expects:
 * parent libraries and arguments are folded into the child, a child may replace a parent library on
 * the same Maven slot, and the effective client jar defaults to the base (non-inheriting) version.</p>
 *
 * <p>Instances are cheap; the repository keeps only a small parsed-results cache and never scans the
 * directory ahead of time, so it stays correct while the launcher adds or removes version folders.</p>
 */
public class VersionRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionRepository.class);

    private final File versionsDirectory;
    private final Map<String, VersionMetadata> cache = new ConcurrentHashMap<>();

    /**
     * @param versionsDirectory the launcher's {@code versions} directory
     */
    public VersionRepository(File versionsDirectory) {
        this.versionsDirectory = versionsDirectory;
    }

    /** The versions directory this repository reads from. */
    public File versionsDirectory() {
        return versionsDirectory;
    }

    /** Drop a cached entry so the next {@link #resolve} re-reads it from disk. */
    public void invalidate(String versionId) {
        cache.remove(versionId);
    }

    /** Drop the whole cache. Call after bulk changes (install, removal, reinstall). */
    public void invalidateAll() {
        cache.clear();
    }

    /** True when {@code id}/{@code id}.json exists in the versions directory. */
    public boolean contains(String versionId) {
        return versionJsonFile(versionId).isFile();
    }

    /** Read the raw JSON of a single version without inheritance resolution, or null if absent. */
    public JsonObject loadRaw(String versionId) {
        File file = versionJsonFile(versionId);
        if (!file.isFile()) {
            return null;
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonElement parsed = com.google.gson.JsonParser.parseString(content);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | com.google.gson.JsonParseException e) {
            LOGGER.warn("Malformed version JSON for {}", versionId, e);
            return null;
        }
    }

    /** The inheritance-merged JSON for a version, or null when it does not exist. */
    public JsonObject resolveRaw(String versionId) throws IOException {
        return resolveChain(versionId, new LinkedHashSet<>()).effectiveJson();
    }

    /**
     * Resolve the typed, inheritance-merged metadata for a version.
     *
     * @throws IOException        when the version is missing or its JSON cannot be parsed
     * @throws VersionChainException when the inheritance chain is circular or refers to a missing parent
     */
    public VersionMetadata resolve(String versionId) throws IOException {
        VersionMetadata cached = cache.get(versionId);
        if (cached != null) {
            return cached;
        }
        Chain chain = resolveChain(versionId, new LinkedHashSet<>());
        VersionMetadata metadata = parseEffective(versionId, chain.effectiveJson(), chain.baseId());
        cache.put(versionId, metadata);
        return metadata;
    }

    /** The base (non-inheriting) version id behind a profile, e.g. {@code 1.21} for a loader install. */
    public String minecraftVersionOf(String versionId) throws IOException {
        return resolve(versionId).minecraftVersion();
    }

    /** Id of the version whose client jar sits on the classpath for the given profile. */
    public String effectiveClientJar(String versionId) throws IOException {
        return resolve(versionId).clientJarId();
    }

    // ------------------------------------------------------------------ chain resolution

    private record Chain(JsonObject effectiveJson, String baseId) {
    }

    private Chain resolveChain(String versionId, Set<String> chain) throws IOException {
        if (versionId == null || versionId.isBlank()) {
            throw new VersionChainException("Version inheritance contains an empty version id");
        }
        if (!chain.add(versionId)) {
            throw new VersionChainException(
                    "Circular version inheritance: " + String.join(" -> ", chain) + " -> " + versionId);
        }
        try {
            JsonObject json = loadRaw(versionId);
            if (json == null) {
                throw new VersionChainException(
                        "Missing version JSON in inheritance chain: " + versionJsonFile(versionId));
            }
            if (!json.has("inheritsFrom") || !json.get("inheritsFrom").isJsonPrimitive()) {
                return new Chain(json, versionId);
            }
            String parentId = json.get("inheritsFrom").getAsString();
            Chain parent = resolveChain(parentId, chain);
            if (!json.has("jar")) {
                String parentJar = effectiveJarOf(parent.effectiveJson());
                if (parentJar != null) {
                    json.addProperty("jar", parentJar);
                } else {
                    json.addProperty("jar", parent.baseId());
                }
            }
            return new Chain(mergeJson(parent.effectiveJson(), json), parent.baseId());
        } finally {
            chain.remove(versionId);
        }
    }

    private static String effectiveJarOf(JsonObject json) {
        if (json != null && json.has("jar") && json.get("jar").isJsonPrimitive()) {
            String jar = json.get("jar").getAsString();
            return jar.isBlank() ? null : jar;
        }
        return null;
    }

    // ------------------------------------------------------------------ JSON merging

    private static JsonObject mergeJson(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            if ("inheritsFrom".equals(key)) {
                continue;
            }
            JsonElement childValue = entry.getValue();
            JsonElement parentValue = result.get(key);
            if ("libraries".equals(key)
                    && parentValue != null && parentValue.isJsonArray() && childValue.isJsonArray()) {
                result.add(key, mergeLibraries(parentValue.getAsJsonArray(), childValue.getAsJsonArray()));
            } else if ("arguments".equals(key)
                    && parentValue != null && parentValue.isJsonObject() && childValue.isJsonObject()) {
                result.add(key, mergeArgumentObjects(parentValue.getAsJsonObject(), childValue.getAsJsonObject()));
            } else {
                result.add(key, childValue.deepCopy());
            }
        }
        return result;
    }

    private static JsonArray mergeLibraries(JsonArray parent, JsonArray child) {
        List<JsonElement> merged = new ArrayList<>();
        Map<String, Integer> keyedIndexes = new HashMap<>();

        for (JsonElement element : parent) {
            addOrReplaceLibrary(merged, keyedIndexes, element);
        }
        for (JsonElement element : child) {
            addOrReplaceLibrary(merged, keyedIndexes, element);
        }

        JsonArray result = new JsonArray();
        merged.forEach(element -> result.add(element.deepCopy()));
        return result;
    }

    private static void addOrReplaceLibrary(List<JsonElement> merged,
                                            Map<String, Integer> keyedIndexes,
                                            JsonElement library) {
        String key = getLibraryIdentity(library);
        Integer existingIndex = key == null ? null : keyedIndexes.get(key);
        if (existingIndex != null) {
            merged.set(existingIndex, library);
            return;
        }
        if (key != null) {
            keyedIndexes.put(key, merged.size());
        }
        merged.add(library);
    }

    /**
     * The Maven group/artifact plus classifier identifies the classpath slot; the child supplies the
     * version. Returns null for entries without a usable coordinate.
     */
    private static String getLibraryIdentity(JsonElement library) {
        if (!library.isJsonObject()) {
            return null;
        }
        JsonObject object = library.getAsJsonObject();
        if (!object.has("name") || !object.get("name").isJsonPrimitive()) {
            return null;
        }
        String[] coordinate = object.get("name").getAsString().split(":", -1);
        if (coordinate.length < 3 || coordinate[0].isBlank() || coordinate[1].isBlank()) {
            return null;
        }
        StringBuilder identity = new StringBuilder(coordinate[0]).append(':').append(coordinate[1]);
        for (int i = 3; i < coordinate.length; i++) {
            identity.append(':').append(coordinate[i]);
        }
        return identity.toString();
    }

    private static JsonObject mergeArgumentObjects(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            JsonElement childValue = entry.getValue();
            JsonElement parentValue = result.get(key);
            if (parentValue != null && parentValue.isJsonArray() && childValue.isJsonArray()) {
                result.add(key, concatenateArrays(parentValue.getAsJsonArray(), childValue.getAsJsonArray()));
            } else if (parentValue != null && parentValue.isJsonObject() && childValue.isJsonObject()) {
                result.add(key, mergeArgumentObjects(parentValue.getAsJsonObject(), childValue.getAsJsonObject()));
            } else {
                result.add(key, childValue.deepCopy());
            }
        }
        return result;
    }

    private static JsonArray concatenateArrays(JsonArray parent, JsonArray child) {
        JsonArray result = parent.deepCopy();
        for (JsonElement element : child) {
            result.add(element.deepCopy());
        }
        return result;
    }

    // ------------------------------------------------------------------ typed conversion

    private static VersionMetadata parseEffective(String requestedId, JsonObject effective, String baseId) {
        int javaMajor = 0;
        JsonElement javaVersion = effective.get("javaVersion");
        if (javaVersion != null && javaVersion.isJsonObject()) {
            javaMajor = JsonUtil.getInt(javaVersion.getAsJsonObject(), "majorVersion", 0);
        }

        AssetIndex assetIndex = null;
        JsonElement assetIndexElement = effective.get("assetIndex");
        if (assetIndexElement != null && assetIndexElement.isJsonObject()) {
            assetIndex = AssetIndex.parse(assetIndexElement.getAsJsonObject());
        }

        Map<String, DownloadObject> downloads = Map.of();
        JsonElement downloadsElement = effective.get("downloads");
        if (downloadsElement != null && downloadsElement.isJsonObject()) {
            JsonObject downloadsObject = downloadsElement.getAsJsonObject();
            Map<String, DownloadObject> parsed = new HashMap<>();
            for (String key : downloadsObject.keySet()) {
                JsonElement value = downloadsObject.get(key);
                if (value != null && value.isJsonObject()) {
                    DownloadObject object = DownloadObject.parse(value.getAsJsonObject());
                    if (object != null) {
                        parsed.put(key, object);
                    }
                }
            }
            if (!parsed.isEmpty()) {
                downloads = Map.copyOf(parsed);
            }
        }

        List<Library> libraries = new ArrayList<>();
        JsonElement librariesElement = effective.get("libraries");
        if (librariesElement != null && librariesElement.isJsonArray()) {
            for (JsonElement element : librariesElement.getAsJsonArray()) {
                Library library = Library.parse(element);
                if (library != null) {
                    libraries.add(library);
                }
            }
        }

        return new VersionMetadata(
                requestedId,
                JsonUtil.getString(effective, "inheritsFrom", null),
                JsonUtil.getString(effective, "mainClass", ""),
                effectiveJarOf(effective),
                firstNonBlank(JsonUtil.getString(effective, "eclMinecraftVersion", null), baseId),
                firstNonBlank(JsonUtil.getString(effective, "eclModLoader", null), null),
                JsonUtil.getString(effective, "type", ""),
                javaMajor,
                assetIndex,
                downloads,
                List.copyOf(libraries),
                VersionArguments.parse(effective));
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private File versionJsonFile(String versionId) {
        return new File(new File(versionsDirectory, versionId), versionId + ".json");
    }
}