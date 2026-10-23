package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.game.ClientLoggingConfig;
import com.ecl.game.DownloadObject;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.NetworkUriPolicy;
import com.ecl.util.RuleEvaluator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only verification of files required by one installed Minecraft version profile. */
public final class GameInstallationVerifier {
    private final Path versions;
    private final Path libraries;
    private final Path assets;

    public GameInstallationVerifier(Path versions, Path libraries, Path assets) {
        this.versions = normalize(versions);
        this.libraries = normalize(libraries);
        this.assets = normalize(assets);
    }

    public static GameInstallationVerifier configured() {
        return new GameInstallationVerifier(ECLConfig.getVersionsDir().toPath(),
                ECLConfig.getLibrariesDir().toPath(), ECLConfig.getAssetsDir().toPath());
    }

    public Report inspect(String profileId) {
        List<Check> checks = new ArrayList<>();
        List<JsonObject> chain = readMetadataChain(profileId, checks);
        if (chain.isEmpty()) return report(profileId, checks);
        JsonObject effective;
        VersionMetadata metadata;
        try {
            effective = new VersionRepository(versions.toFile()).resolveRaw(profileId);
            metadata = new VersionRepository(versions.toFile()).resolve(profileId);
        } catch (IOException | RuntimeException invalid) {
            checks.add(new Check(Category.METADATA, versions.resolve(profileId).toString(),
                    Status.UNVERIFIABLE, invalid.getMessage(), false));
            return report(profileId, checks);
        }
        verifyClient(metadata, effective, checks);
        verifyLibraries(effective, checks);
        verifyAssets(effective, checks);
        verifyClientLogging(metadata, checks);
        return report(profileId, checks);
    }

    /**
     * Check the legacy {@code logging.client} Log4j configuration. The declared file is only
     * repairable when the metadata carries a usable URL, SHA-1 and size, matching the other
     * categories — the launcher never guesses a source for it.
     */
    private void verifyClientLogging(VersionMetadata metadata, List<Check> checks) {
        ClientLoggingConfig logging = metadata.clientLogging();
        if (logging == null) {
            return;
        }
        Path file;
        try {
            file = logging.targetFile(assets.toFile()).toPath();
        } catch (IOException invalidTarget) {
            checks.add(new Check(Category.LOGGING, logging.relativePath(), Status.UNVERIFIABLE,
                    message(invalidTarget), false));
            return;
        }
        DownloadObject object = logging.file();
        boolean repairable = usableUrl(object.url()) && object.hasChecksum() && object.size() > 0;
        addVerifiedOrCorrupt(Category.LOGGING, file, object.sha1(), object.size(), repairable, checks);
    }

    private static void addVerifiedOrCorrupt(Category category, Path file, String sha1, long size,
                                             boolean repairable, List<Check> checks) {
        if (!sha1.matches("(?i)[0-9a-f]{40}") || size <= 0) {
            checks.add(new Check(category, file.toString(), Status.UNVERIFIABLE,
                    "Expected SHA-1 or size is missing", false));
            return;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            checks.add(new Check(category, file.toString(), Status.MISSING, "File is missing",
                    repairable));
            return;
        }
        try {
            if (Files.size(file) != size || !FileUtil.verifySha1(file.toFile(), sha1)) {
                checks.add(new Check(category, file.toString(), Status.CORRUPT,
                        "File size or SHA-1 does not match metadata", repairable));
                return;
            }
            checks.add(new Check(category, file.toString(), Status.VERIFIED, "", false));
        } catch (IOException error) {
            checks.add(new Check(category, file.toString(), Status.UNVERIFIABLE, message(error),
                    false));
        }
    }

    private Report report(String profileId, List<Check> checks) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        for (Check check : checks) {
            add(digest, check.category().name());
            add(digest, check.path());
            add(digest, check.status().name());
            Path file;
            try {
                file = Path.of(check.path()).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                continue;
            }
            Path root = rootFor(file);
            if (root == null) continue;
            try {
                FileUtil.validateExistingAncestors(root, file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    add(digest, "missing");
                    continue;
                }
                add(digest, Long.toString(Files.size(file)));
                add(digest, Long.toString(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis()));
                if (check.category() == Category.METADATA || check.status() != Status.VERIFIED) {
                    try (InputStream input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read > 0) digest.update(buffer, 0, read);
                        }
                    }
                }
            } catch (IOException unreadable) {
                add(digest, "unreadable:" + message(unreadable));
            }
        }
        return new Report(profileId, checks, HexFormat.of().formatHex(digest.digest()));
    }

    private Path rootFor(Path file) {
        if (file.startsWith(versions)) return versions;
        if (file.startsWith(libraries)) return libraries;
        if (file.startsWith(assets)) return assets;
        return null;
    }

    private static void add(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private List<JsonObject> readMetadataChain(String profileId, List<Check> checks) {
        List<JsonObject> chain = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String current = profileId;
        while (current != null && !current.isBlank()) {
            try {
                FileUtil.requireSafeVersionId(current);
                if (!seen.add(current)) {
                    checks.add(new Check(Category.METADATA, current, Status.CORRUPT,
                            "Circular inheritance chain", false));
                    return List.of();
                }
                Path file = FileUtil.safeVersionJson(versions.toFile(), current).toPath();
                FileUtil.validateExistingAncestors(versions, file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    checks.add(new Check(Category.METADATA, file.toString(), Status.MISSING,
                            "Version metadata is missing", false));
                    return List.of();
                }
                JsonObject json = HttpUtil.readJson(file.toFile());
                checks.add(new Check(Category.METADATA, file.toString(), Status.VERIFIED, "", false));
                chain.add(json);
                current = JsonUtil.getString(json, "inheritsFrom", "");
            } catch (IOException | RuntimeException invalid) {
                checks.add(new Check(Category.METADATA, current, Status.CORRUPT,
                        message(invalid), false));
                return List.of();
            }
        }
        return chain;
    }

    private void verifyClient(VersionMetadata metadata, JsonObject effective, List<Check> checks) {
        JsonObject downloads = object(effective, "downloads");
        JsonObject client = object(downloads, "client");
        String jarId = metadata.clientJarId();
        try {
            FileUtil.requireSafeVersionId(jarId);
            Path jar = FileUtil.safeVersionJar(versions.toFile(), jarId).toPath();
            if (client == null) {
                checks.add(new Check(Category.CLIENT, jar.toString(), Status.UNVERIFIABLE,
                        "Metadata does not declare a verified client download", false));
            } else {
                checks.add(check(Category.CLIENT, jar, client, usableUrl(string(client, "url"))));
            }
        } catch (IOException | RuntimeException invalid) {
            checks.add(new Check(Category.CLIENT, jarId, Status.UNVERIFIABLE, message(invalid), false));
        }
    }

    private void verifyLibraries(JsonObject profile, List<Check> checks) {
        JsonArray entries = profile.has("libraries") && profile.get("libraries").isJsonArray()
                ? profile.getAsJsonArray("libraries") : new JsonArray();
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String bits = architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
        String nativeClassifier = com.ecl.util.OperatingSystem.minecraftName() + "-"
                + FileUtil.nativeArchitecture(architecture);
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                checks.add(new Check(Category.LIBRARY, "libraries", Status.UNVERIFIABLE,
                        "Library metadata is malformed", false));
                continue;
            }
            JsonObject library = element.getAsJsonObject();
            try {
                if (library.has("rules") && !RuleEvaluator.isAllowed(library.getAsJsonArray("rules"))) {
                    continue;
                }
                String name = JsonUtil.getString(library, "name", "");
                Category category = isLoaderLibrary(name)
                        ? Category.LOADER_DEPENDENCY : Category.LIBRARY;
                JsonObject downloads = object(library, "downloads");
                if (downloads != null && object(downloads, "artifact") != null) {
                    checks.add(checkManaged(category, object(downloads, "artifact"), libraries));
                } else if (downloads == null || library.has("url") && downloads.has("classifiers")) {
                    checkMavenLibrary(library, category, checks);
                }
                JsonObject classifiers = object(downloads, "classifiers");
                String key = classifiers == null ? null : GameDownloader.nativeClassifierKey(
                        library, classifiers, com.ecl.util.OperatingSystem.minecraftName(), bits,
                        nativeClassifier);
                if (key != null) {
                    checks.add(checkManaged(Category.NATIVE, object(classifiers, key), libraries));
                }
            } catch (IOException | RuntimeException invalid) {
                checks.add(new Check(Category.LIBRARY, JsonUtil.getString(library, "name", ""),
                        Status.UNVERIFIABLE, message(invalid), false));
            }
        }
    }

    private void checkMavenLibrary(JsonObject library, Category category, List<Check> checks)
            throws IOException {
        String coordinate = JsonUtil.getString(library, "name", "");
        String repository = JsonUtil.getString(library, "url", "");
        if (!com.ecl.game.MavenCoordinates.isSimpleCoordinate(coordinate) || repository.isBlank()) {
            checks.add(new Check(category, coordinate, Status.UNVERIFIABLE,
                    "No trusted artifact path or checksum is declared", false));
            return;
        }
        Path file = FileUtil.safeResolveUnder(libraries.toFile(),
                com.ecl.game.MavenCoordinates.repositoryPath(coordinate)).toPath();
        boolean repairable = usableUrl(com.ecl.game.MavenCoordinates.repositoryUrl(repository, coordinate));
        checks.add(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                ? new Check(category, file.toString(), Status.UNVERIFIABLE,
                        "Artifact has no declared checksum or size", false)
                : new Check(category, file.toString(), Status.MISSING,
                        "Artifact is missing and must be verified before repair", repairable));
    }

    private void verifyAssets(JsonObject profile, List<Check> checks) {
        JsonObject index = object(profile, "assetIndex");
        if (index == null) {
            checks.add(new Check(Category.ASSET_INDEX, "", Status.UNVERIFIABLE,
                    "Version metadata does not declare an asset index", false));
            return;
        }
        String id = JsonUtil.getString(index, "id", "");
        Path indexFile;
        try {
            FileUtil.requireSafeVersionId(id);
            indexFile = FileUtil.safeResolveUnder(assets.toFile(), "indexes/" + id + ".json").toPath();
        } catch (IOException invalid) {
            checks.add(new Check(Category.ASSET_INDEX, id, Status.UNVERIFIABLE,
                    message(invalid), false));
            return;
        }
        Check indexCheck = check(Category.ASSET_INDEX, indexFile, index,
                usableUrl(JsonUtil.getString(index, "url", "")));
        checks.add(indexCheck);
        if (indexCheck.status() != Status.VERIFIED) return;
        JsonObject objects;
        try {
            objects = object(HttpUtil.readJson(indexFile.toFile()), "objects");
        } catch (IOException | RuntimeException invalid) {
            checks.add(new Check(Category.ASSET_INDEX, indexFile.toString(), Status.CORRUPT,
                    message(invalid), false));
            return;
        }
        if (objects == null) {
            checks.add(new Check(Category.ASSET_INDEX, indexFile.toString(), Status.CORRUPT,
                    "Asset index has no object list", false));
            return;
        }
        for (String name : objects.keySet()) {
            JsonObject asset = object(objects, name);
            if (asset == null) {
                checks.add(new Check(Category.ASSET_OBJECT, name, Status.UNVERIFIABLE,
                        "Asset object metadata is malformed", false));
                continue;
            }
            String hash = JsonUtil.getString(asset, "hash", "");
            if (!hash.matches("(?i)[0-9a-f]{40}")) {
                checks.add(new Check(Category.ASSET_OBJECT, name, Status.UNVERIFIABLE,
                        "Asset object has no valid SHA-1", false));
                continue;
            }
            try {
                Path file = FileUtil.safeResolveUnder(assets.resolve("objects").toFile(),
                        hash.substring(0, 2) + "/" + hash).toPath();
                JsonObject expected = asset.deepCopy();
                expected.addProperty("sha1", hash);
                checks.add(check(Category.ASSET_OBJECT, file, expected, true));
            } catch (IOException invalid) {
                checks.add(new Check(Category.ASSET_OBJECT, name, Status.UNVERIFIABLE,
                        message(invalid), false));
            }
        }
    }

    private Check checkManaged(Category category, JsonObject artifact, Path root) throws IOException {
        if (artifact == null) {
            return new Check(category, root.toString(), Status.UNVERIFIABLE,
                    "Artifact metadata is missing", false);
        }
        String path = JsonUtil.getString(artifact, "path", "");
        if (path.isBlank()) {
            return new Check(category, "", Status.UNVERIFIABLE,
                    "Artifact path is missing", false);
        }
        File file = FileUtil.safeResolveUnder(root.toFile(), path);
        return check(category, file.toPath(), artifact, usableUrl(JsonUtil.getString(artifact, "url", "")));
    }

    private static Check check(Category category, Path file, JsonObject metadata, boolean repairable) {
        String sha1 = JsonUtil.getString(metadata, "sha1", "");
        long size = JsonUtil.getLong(metadata, "size", -1L);
        boolean sizeRequired = category == Category.CLIENT || category == Category.ASSET_INDEX
                || category == Category.ASSET_OBJECT;
        if (!sha1.matches("(?i)[0-9a-f]{40}") || sizeRequired && size <= 0) {
            return new Check(category, file.toString(), Status.UNVERIFIABLE,
                    "Expected SHA-1 or size is missing", false);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Check(category, file.toString(), Status.MISSING, "File is missing", repairable);
        }
        try {
            if (size > 0 && Files.size(file) != size || !FileUtil.verifySha1(file.toFile(), sha1)) {
                return new Check(category, file.toString(), Status.CORRUPT,
                        "File size or SHA-1 does not match metadata", repairable);
            }
            return new Check(category, file.toString(), Status.VERIFIED,
                    size > 0 ? "" : "SHA-1 verified; metadata does not declare a size", false);
        } catch (IOException error) {
            return new Check(category, file.toString(), Status.UNVERIFIABLE, message(error), false);
        }
    }

    private static boolean usableUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())
                    && NetworkUriPolicy.isLoopbackHostLiteral(uri.getHost()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isLoaderLibrary(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("fabric-loader") || normalized.contains("quilt-loader")
                || normalized.contains("minecraftforge") || normalized.contains("forge:")
                || normalized.contains("neoforge");
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject object, String key) {
        return object == null ? "" : JsonUtil.getString(object, key, "");
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public enum Category { METADATA, CLIENT, LIBRARY, NATIVE, ASSET_INDEX, ASSET_OBJECT,
        LOADER_DEPENDENCY, LOGGING }

    public enum Status { VERIFIED, MISSING, CORRUPT, UNVERIFIABLE }

    public record Check(Category category, String path, Status status, String reason,
                        boolean repairable) { }

    public record Report(String profileId, List<Check> checks, String fingerprint) {
        public Report {
            profileId = profileId == null ? "" : profileId;
            checks = checks == null ? List.of() : List.copyOf(checks);
            fingerprint = fingerprint == null ? "" : fingerprint;
        }

        public boolean complete() {
            return checks.stream().allMatch(check -> check.status() == Status.VERIFIED);
        }

        public long count(Status status) {
            return checks.stream().filter(check -> check.status() == status).count();
        }

        public List<Check> issues() {
            return checks.stream().filter(check -> check.status() != Status.VERIFIED).toList();
        }
    }
}
