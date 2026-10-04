package com.ecl.modrinth.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Files owned by one installed Modrinth pack version. */
final class PackManifest {
    static final String FILE_NAME = ".ecl-pack-manifest.json";
    private static final int FORMAT_VERSION = 1;

    private final String packVersion;
    private final Map<String, String> files;

    PackManifest(String packVersion, Map<String, String> files) {
        this.packVersion = packVersion == null ? "" : packVersion;
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(files)));
    }

    String packVersion() {
        return packVersion;
    }

    Map<String, String> files() {
        return files;
    }

    static PackManifest capture(Path root, String packVersion) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        MrpackPathPolicy.validateExistingAncestors(normalizedRoot, normalizedRoot);
        Map<String, String> entries = new TreeMap<>();
        try (var paths = Files.walk(normalizedRoot)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(
                    path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                MrpackPathPolicy.validateExistingAncestors(normalizedRoot, file);
                String relative = portable(normalizedRoot.relativize(file));
                if (!FILE_NAME.equals(relative)) {
                    entries.put(relative, sha512(file));
                }
            }
        }
        return new PackManifest(packVersion, entries);
    }

    static PackManifest read(Path manifestFile) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(manifestFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack manifest: " + manifestFile, error);
        }
        try {
            if (!root.has("formatVersion")
                    || root.get("formatVersion").getAsInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported pack manifest format: " + manifestFile);
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack manifest format: " + manifestFile, error);
        }
        String version;
        try {
            version = root.has("packVersion") ? root.get("packVersion").getAsString() : "";
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack version in manifest: " + manifestFile, error);
        }
        JsonArray items = root.has("files") && root.get("files").isJsonArray()
                ? root.getAsJsonArray("files") : new JsonArray();
        Map<String, String> entries = new TreeMap<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                throw new IOException("Invalid pack manifest entry: " + manifestFile);
            }
            JsonObject item = element.getAsJsonObject();
            String relative;
            String hash;
            try {
                relative = item.has("path") ? item.get("path").getAsString() : "";
                hash = item.has("sha512") ? item.get("sha512").getAsString() : "";
            } catch (RuntimeException error) {
                throw new IOException("Invalid pack manifest entry: " + manifestFile, error);
            }
            validateRelative(relative);
            if (!hash.matches("(?i)[0-9a-f]{128}")) {
                throw new IOException("Invalid SHA-512 in pack manifest: " + relative);
            }
            if (entries.put(relative, hash.toLowerCase(java.util.Locale.ROOT)) != null) {
                throw new IOException("Duplicate path in pack manifest: " + relative);
            }
        }
        return new PackManifest(version, entries);
    }

    void write(Path manifestFile) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("packVersion", packVersion);
        JsonArray items = new JsonArray();
        files.forEach((path, hash) -> {
            JsonObject item = new JsonObject();
            item.addProperty("path", path);
            item.addProperty("sha512", hash);
            items.add(item);
        });
        root.add("files", items);
        Files.createDirectories(manifestFile.toAbsolutePath().normalize().getParent());
        Files.writeString(manifestFile, root.toString(), StandardCharsets.UTF_8);
    }

    static String sha512(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("Current Java runtime does not support SHA-512", impossible);
        }
    }

    static Path resolve(Path root, String relative) throws IOException {
        validateRelative(relative);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar))
                .normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("Pack manifest path escapes instance: " + relative);
        }
        MrpackPathPolicy.validateExistingAncestors(normalizedRoot, result);
        return result;
    }

    private static void validateRelative(String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0
                || relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) {
            throw new IOException("Invalid pack manifest path: " + relative);
        }
        Path path;
        try {
            path = Path.of(relative).normalize();
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack manifest path: " + relative, error);
        }
        if (path.isAbsolute() || path.startsWith("..") || ".".equals(path.toString())) {
            throw new IOException("Invalid pack manifest path: " + relative);
        }
    }

    private static String portable(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
