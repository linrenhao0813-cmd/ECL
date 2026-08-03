package com.ecl.auth;

import com.ecl.ECLConfig;
import com.ecl.util.CryptoUtil;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persistent encrypted Microsoft account list used for account switching. */
public final class MicrosoftAccountStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftAccountStore.class);
    private final Path file;

    public MicrosoftAccountStore() {
        this(ECLConfig.getBaseDir().toPath().resolve("microsoft-accounts.json"));
    }

    MicrosoftAccountStore(Path file) {
        this.file = file;
    }

    public synchronized List<Account> list() {
        JsonArray raw = readRaw();
        if (raw == null) return List.of();
        List<Account> accounts = new ArrayList<>();
        for (JsonElement item : raw) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String uuid = string(object, "uuid");
            String username = string(object, "username");
            if (uuid.isBlank() || username.isBlank()) continue;
            accounts.add(new Account(
                    uuid,
                    username,
                    CryptoUtil.decrypt(string(object, "refreshToken")),
                    CryptoUtil.decrypt(string(object, "accessToken")),
                    longValue(object, "accessTokenExpiresAt")));
        }
        return List.copyOf(accounts);
    }

    public synchronized boolean save(Account account) {
        if (account == null || account.uuid() == null || account.uuid().isBlank()
                || account.username() == null || account.username().isBlank()) {
            return false;
        }
        JsonArray raw = readRaw();
        if (raw == null) return false;
        JsonObject previous = null;
        int previousIndex = -1;
        for (int i = 0; i < raw.size(); i++) {
            JsonElement item = raw.get(i);
            if (item.isJsonObject()
                    && account.uuid().equalsIgnoreCase(string(item.getAsJsonObject(), "uuid"))) {
                previous = item.getAsJsonObject();
                previousIndex = i;
                break;
            }
        }
        String encryptedRefresh = encryptOrPreserve(
                account.refreshToken(), previous, "refreshToken");
        String encryptedAccess = encryptOrPreserve(
                account.accessToken(), previous, "accessToken");
        if ((account.refreshToken() != null && !account.refreshToken().isBlank()
                && encryptedRefresh.isBlank())
                || (account.accessToken() != null && !account.accessToken().isBlank()
                && encryptedAccess.isBlank())) {
            return false;
        }
        JsonObject replacement = new JsonObject();
        replacement.addProperty("uuid", account.uuid());
        replacement.addProperty("username", account.username());
        replacement.addProperty("refreshToken", encryptedRefresh);
        replacement.addProperty("accessToken", encryptedAccess);
        replacement.addProperty("accessTokenExpiresAt", account.accessTokenExpiresAt());
        if (previousIndex >= 0) raw.set(previousIndex, replacement);
        else raw.add(replacement);
        return write(raw);
    }

    public synchronized boolean remove(String uuid) {
        JsonArray raw = readRaw();
        if (raw == null) return false;
        for (int i = raw.size() - 1; i >= 0; i--) {
            JsonElement item = raw.get(i);
            if (item.isJsonObject()
                    && uuid.equalsIgnoreCase(string(item.getAsJsonObject(), "uuid"))) {
                raw.remove(i);
            }
        }
        return write(raw);
    }

    private JsonArray readRaw() {
        if (!Files.isRegularFile(file)) return new JsonArray();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonArray()) return root.getAsJsonArray();
            LOGGER.warn("Microsoft account store root is not an array: {}", file);
        } catch (Exception error) {
            LOGGER.warn("Failed to load Microsoft account store {}", file, error);
        }
        return null;
    }

    private String encryptOrPreserve(String plaintext, JsonObject previous, String key) {
        if (plaintext == null || plaintext.isBlank()) {
            return previous == null ? "" : string(previous, key);
        }
        return CryptoUtil.encrypt(plaintext);
    }

    private boolean write(JsonArray array) {
        Path temp = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            temp = Files.createTempFile(file.toAbsolutePath().getParent(),
                    "microsoft-accounts-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(array, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException error) {
            LOGGER.error("Failed to save Microsoft account store {}", file, error);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static long longValue(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : 0;
        } catch (RuntimeException error) {
            return 0;
        }
    }

    public record Account(String uuid, String username, String refreshToken,
                          String accessToken, long accessTokenExpiresAt) {
        @Override
        public String toString() {
            return username;
        }
    }
}
