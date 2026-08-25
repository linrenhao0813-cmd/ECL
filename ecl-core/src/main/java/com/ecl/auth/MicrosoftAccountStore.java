package com.ecl.auth;

import com.ecl.ECLConfig;
import com.ecl.util.CryptoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent encrypted Microsoft account list used for account switching. */
public final class MicrosoftAccountStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftAccountStore.class);
    private final Path file;
    private final DefaultAccountService accounts;

    public MicrosoftAccountStore() {
        this.file = ECLConfig.getBaseDir().toPath().resolve("microsoft-accounts.json");
        this.accounts = new DefaultAccountService();
        migrateLegacyAccounts();
    }

    MicrosoftAccountStore(Path file) {
        this.file = file;
        this.accounts = new DefaultAccountService(file, new AuthProviderRegistry());
    }

    public synchronized List<Account> list() {
        return accounts.list().stream()
                .filter(account -> account.type() == AuthType.MICROSOFT)
                .map(account -> new Account(account.uuid(), account.username(),
                        account.refreshToken(), account.accessToken(), account.tokenExpiry()))
                .toList();
    }

    public synchronized boolean save(Account account) {
        if (account == null || account.uuid() == null || account.uuid().isBlank()
                || account.username() == null || account.username().isBlank()) {
            return false;
        }
        try {
            List<AuthAccount> existing = accounts.list();
            boolean selected = existing.stream()
                    .filter(saved -> saved.identity().equalsIgnoreCase(
                            AuthType.MICROSOFT.name() + ":" + account.uuid().toLowerCase()))
                    .findFirst()
                    .map(AuthAccount::defaultAccount)
                    .orElseGet(() -> existing.stream().noneMatch(AuthAccount::defaultAccount));
            accounts.save(new AuthAccount(AuthType.MICROSOFT, account.uuid(), account.username(),
                    account.username(), account.accessToken(), account.refreshToken(),
                    account.accessTokenExpiresAt(), "", selected));
            return true;
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to save Microsoft account in unified repository", failure);
            return false;
        }
    }

    public synchronized boolean remove(String uuid) {
        return accounts.remove(AuthType.MICROSOFT.name() + ":" + uuid.toLowerCase());
    }

    private void migrateLegacyAccounts() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonArray raw = readRaw();
            if (raw == null) return;
            List<Account> legacy = new ArrayList<>();
            for (JsonElement item : raw) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                String uuid = string(object, "uuid");
                String username = string(object, "username");
                if (uuid.isBlank() || username.isBlank()) continue;
                legacy.add(new Account(uuid, username,
                        decryptLegacy(object, "refreshToken"), decryptLegacy(object, "accessToken"),
                        longValue(object, "accessTokenExpiresAt")));
            }
            Set<String> existingIdentities = new HashSet<>();
            accounts.list().forEach(account -> existingIdentities.add(account.identity().toLowerCase()));
            for (Account account : legacy) {
                String identity = AuthType.MICROSOFT.name().toLowerCase() + ":"
                        + account.uuid().toLowerCase();
                if (existingIdentities.contains(identity)) continue;
                if (!save(account)) throw new IllegalStateException("Unified account save failed");
                existingIdentities.add(identity);
            }
            archiveMigratedStore();
        } catch (RuntimeException failure) {
            LOGGER.error("Legacy Microsoft accounts were not migrated; the old file was preserved", failure);
        } catch (IOException failure) {
            LOGGER.error("Legacy Microsoft accounts were imported but the old file could not be archived", failure);
        }
    }

    private void archiveMigratedStore() throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".migrated");
        int suffix = 2;
        while (Files.exists(backup)) {
            backup = file.resolveSibling(file.getFileName() + ".migrated-" + suffix++);
        }
        try {
            Files.move(file, backup, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(file, backup);
        }
    }

    private static String decryptLegacy(JsonObject object, String key) {
        String encrypted = string(object, key);
        if (encrypted.isBlank()) {
            return "";
        }
        if (!looksLikeCiphertext(encrypted)) {
            // 旧文件中的明文或损坏值不是本版本密文格式：跳过该字段并继续迁移其余账号，
            // 让用户重新登录即可，而不是中止整批迁移。
            LOGGER.warn("Legacy Microsoft account field '{}' is not encrypted ciphertext; "
                    + "skipping its migration", key);
            return "";
        }
        try {
            return CryptoUtil.decrypt(encrypted);
        } catch (IllegalStateException failure) {
            LOGGER.warn("Failed to decrypt legacy Microsoft account field '{}'; "
                    + "skipping its migration", key, failure);
            return "";
        }
    }

    /**
     * Ciphertext produced by {@link CryptoUtil#encrypt(String)} is Base64 of at least
     * IV(12) + tag(16) bytes; plaintext or truncated values are rejected before decryption.
     */
    private static boolean looksLikeCiphertext(String value) {
        if (value == null || value.length() < 24) {
            return false;
        }
        try {
            return java.util.Base64.getDecoder().decode(value).length >= 13;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
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
