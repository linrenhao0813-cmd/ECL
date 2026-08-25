package com.ecl.auth;

import com.ecl.ECLConfig;
import com.ecl.exception.AuthException;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores locally imported skins for offline accounts.
 *
 * <p>Each imported skin is copied into {@code <base>/skins/<sha1(identity)>.png} so it survives
 * the original file being moved or deleted, and a small {@code skins.json} index keeps the
 * character model choice per account identity. Skins are keyed by the same identity used by
 * {@link AuthAccount#identity()} so an account owns exactly one skin.</p>
 */
public final class OfflineSkinStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfflineSkinStore.class);
    private static final Object STORE_LOCK = new Object();
    private static final String INDEX_FILE = "skins.json";
    private static final String DIR_NAME = "skins";

    private final Path baseDir;
    private final Path skinsDir;
    private final Path indexFile;

    public OfflineSkinStore() {
        this(ECLConfig.getBaseDir().toPath());
    }

    OfflineSkinStore(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.skinsDir = this.baseDir.resolve(DIR_NAME);
        this.indexFile = this.baseDir.resolve(INDEX_FILE);
    }

    /** Identity of an ad-hoc offline account (never persisted) derived from its player name. */
    public static String identityForOffline(String username) {
        String name = username == null ? "" : username.trim();
        return AuthType.OFFLINE.name() + ":" + new OfflineAuth(name).getUUID().toLowerCase(Locale.ROOT);
    }

    /**
     * Validate {@code png} and copy it into the launcher data directory for {@code identity}.
     * Replaces any previously imported skin for the same identity.
     */
    public OfflineSkin importSkin(String identity, Path png,
                                  MinecraftSkinService.Variant variant) throws IOException {
        synchronized (STORE_LOCK) {
            return importSkinLocked(normalizeIdentity(identity), png, variant);
        }
    }

    private OfflineSkin importSkinLocked(String identity, Path png,
                                         MinecraftSkinService.Variant variant) throws IOException {
        MinecraftSkinService.SkinImage skin = new MinecraftSkinService().inspect(png);
        Files.createDirectories(skinsDir);
        String fileName = sha1Hex(identity.toLowerCase(Locale.ROOT)) + ".png";
        Path target = skinsDir.resolve(fileName);
        MinecraftSkinService.Variant selected =
                variant == null ? MinecraftSkinService.Variant.CLASSIC : variant;
        Path staged = Files.createTempFile(skinsDir, "skin-", ".png.tmp");
        Path backup = null;
        try {
            Files.write(staged, skin.imageBytes());
            if (Files.isRegularFile(target)) {
                backup = Files.createTempFile(skinsDir, "skin-backup-", ".png.tmp");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveReplacing(staged, target);

            JsonObject index = readIndex();
            JsonObject entry = new JsonObject();
            entry.addProperty("file", DIR_NAME + "/" + fileName);
            entry.addProperty("variant", selected.name().toLowerCase(Locale.ROOT));
            index.add(identity, entry);
            try {
                writeIndex(index);
            } catch (RuntimeException failure) {
                rollbackSkin(target, backup, failure);
                throw failure;
            }
            return new OfflineSkin(identity, target, selected);
        } finally {
            deleteTemporary(staged);
            if (backup != null) {
                deleteTemporary(backup);
            }
        }
    }

    /** The imported skin for {@code identity}, if any. */
    public Optional<OfflineSkin> find(String identity) {
        if (identity == null || identity.isBlank()) {
            return Optional.empty();
        }
        synchronized (STORE_LOCK) {
            return findLocked(normalizeIdentity(identity));
        }
    }

    private Optional<OfflineSkin> findLocked(String identity) {
        JsonObject index = readIndex();
        JsonElement raw = index.get(identity);
        if (raw == null || !raw.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject entry = raw.getAsJsonObject();
        Optional<Path> storedPath = storedPath(entry);
        if (storedPath.isEmpty()) {
            return Optional.empty();
        }
        Path png = storedPath.get();
        String variantText = text(entry, "variant", "classic");
        MinecraftSkinService.Variant variant = MinecraftSkinService.Variant.CLASSIC;
        try {
            variant = MinecraftSkinService.Variant.valueOf(variantText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Unknown variant falls back to classic
        }
        return Optional.of(new OfflineSkin(identity, png, variant));
    }

    /** Remove the imported skin (if any) for {@code identity}. Returns whether one existed. */
    public boolean remove(String identity) {
        if (identity == null || identity.isBlank()) {
            return false;
        }
        synchronized (STORE_LOCK) {
            return removeLocked(normalizeIdentity(identity));
        }
    }

    private boolean removeLocked(String identity) {
        JsonObject index = readIndex();
        JsonElement raw = index.remove(identity);
        if (raw == null) {
            return false;
        }
        if (raw.isJsonObject()) {
            Optional<Path> storedPath = storedPath(raw.getAsJsonObject());
            writeIndex(index);
            if (storedPath.isPresent()) {
                Path png = storedPath.get();
                try {
                    Files.deleteIfExists(png);
                } catch (IOException failure) {
                    LOGGER.warn("Cannot delete unreferenced offline skin {}", png, failure);
                }
            }
        } else {
            writeIndex(index);
        }
        return true;
    }

    private JsonObject readIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Cannot read offline skin index {}; treating it as empty", indexFile, failure);
            return new JsonObject();
        }
    }

    private static String normalizeIdentity(String identity) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("Account identity is required");
        }
        String value = identity.trim();
        int separator = value.indexOf(':');
        if (separator <= 0 || !AuthType.OFFLINE.name().equalsIgnoreCase(value.substring(0, separator))) {
            throw new IllegalArgumentException("Offline account identity is required");
        }
        String accountId = value.substring(separator + 1).trim();
        if (accountId.isEmpty()) {
            throw new IllegalArgumentException("Offline account identity is required");
        }
        return AuthType.OFFLINE.name() + ":" + accountId.toLowerCase(Locale.ROOT);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rollbackSkin(Path target, Path backup, RuntimeException failure) {
        try {
            if (backup == null) {
                Files.deleteIfExists(target);
            } else {
                moveReplacing(backup, target);
            }
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private Optional<Path> storedPath(JsonObject entry) {
        try {
            String relative = text(entry, "file", "");
            Path png = baseDir.resolve(relative).normalize();
            if (!png.startsWith(skinsDir) || !Files.isRegularFile(png)) {
                return Optional.empty();
            }
            Path realSkinsDir = skinsDir.toRealPath();
            Path realPng = png.toRealPath();
            return realPng.startsWith(realSkinsDir) ? Optional.of(realPng) : Optional.empty();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Ignoring invalid offline skin index entry", failure);
            return Optional.empty();
        }
    }

    private static void deleteTemporary(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            LOGGER.warn("Cannot delete temporary offline skin file {}", file, failure);
        }
    }

    private void writeIndex(JsonObject index) {
        Path absolute = indexFile.toAbsolutePath();
        Path parent = absolute.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "skins-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(index, writer);
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new AuthException("无法保存皮肤索引文件", failure);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the temporary index file only.
                }
            }
        }
    }

    private static String text(JsonObject item, String key, String fallback) {
        try {
            return item.has(key) && !item.get(key).isJsonNull() ? item.get(key).getAsString() : fallback;
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 not available", impossible);
        }
    }
}
