package com.ecl.auth;

import com.ecl.ECLConfig;
import com.ecl.exception.AuthException;
import com.ecl.util.CryptoUtil;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JSON account repository with AES-GCM encrypted credentials and atomic writes. */
public final class DefaultAccountService implements AccountService {
    private final Path file;
    private final AuthProviderRegistry providers;

    public DefaultAccountService() {
        this(ECLConfig.getBaseDir().toPath().resolve("accounts.json"), new AuthProviderRegistry());
    }

    DefaultAccountService(Path file, AuthProviderRegistry providers) {
        this.file = file;
        this.providers = providers;
    }

    @Override
    public synchronized List<AuthAccount> list() {
        JsonArray array = read();
        List<AuthAccount> accounts = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            try {
                AuthAccount account = decode(item);
                if (!account.username().isBlank()) accounts.add(account);
            } catch (RuntimeException failure) {
                throw new AuthException("Account file contains an unreadable entry; no changes were made", failure);
            }
        }
        return List.copyOf(accounts);
    }

    @Override
    public synchronized AuthAccount save(AuthAccount account) {
        if (account == null || account.username().isBlank()) {
            throw new IllegalArgumentException("Account username is required");
        }
        List<AuthAccount> accounts = new ArrayList<>(list());
        accounts.removeIf(existing -> existing.identity().equalsIgnoreCase(account.identity()));
        if (account.defaultAccount()) {
            accounts.replaceAll(existing -> withDefault(existing, false));
        }
        accounts.add(account);
        write(accounts);
        return account;
    }

    @Override
    public AuthAccount addOffline(String username) {
        OfflineAuth provider = new OfflineAuth(username);
        AuthAccount account = new AuthAccount(AuthType.OFFLINE, provider.getUUID(), provider.getUsername(),
                provider.getUsername(), "", "", 0, "", list().isEmpty());
        return save(account);
    }

    @Override
    public synchronized boolean remove(String identity) {
        List<AuthAccount> accounts = new ArrayList<>(list());
        boolean changed = accounts.removeIf(account -> account.identity().equalsIgnoreCase(identity));
        if (changed) {
            if (!accounts.isEmpty() && accounts.stream().noneMatch(AuthAccount::defaultAccount)) {
                accounts.set(0, withDefault(accounts.get(0), true));
            }
            write(accounts);
        }
        return changed;
    }

    @Override
    public Optional<AuthAccount> defaultAccount() {
        List<AuthAccount> accounts = list();
        return accounts.stream().filter(AuthAccount::defaultAccount).findFirst()
                .or(() -> accounts.stream().findFirst());
    }

    @Override
    public synchronized void setDefault(String identity) {
        List<AuthAccount> accounts = new ArrayList<>(list());
        boolean found = accounts.stream().anyMatch(account -> account.identity().equalsIgnoreCase(identity));
        if (!found) throw new IllegalArgumentException("Unknown account: " + identity);
        accounts.replaceAll(account -> withDefault(account, account.identity().equalsIgnoreCase(identity)));
        write(accounts);
    }

    @Override
    public AuthProvider createProvider(AuthAccount account) {
        return providers.create(account);
    }

    private JsonArray read() {
        if (!Files.isRegularFile(file)) return new JsonArray();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        } catch (IOException | RuntimeException failure) {
            throw new AuthException("无法读取账户文件", failure);
        }
    }

    private void write(List<AuthAccount> accounts) {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "accounts-", ".tmp");
            JsonArray array = new JsonArray();
            accounts.forEach(account -> array.add(encode(account)));
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(array, writer);
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new AuthException("无法保存账户文件", failure);
        } finally {
            if (temp != null) try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    private static JsonObject encode(AuthAccount account) {
        JsonObject item = new JsonObject();
        item.addProperty("type", account.type().name());
        item.addProperty("uuid", account.uuid());
        item.addProperty("username", account.username());
        item.addProperty("displayName", account.displayName());
        item.addProperty("accessToken", CryptoUtil.encrypt(account.accessToken()));
        item.addProperty("refreshToken", CryptoUtil.encrypt(account.refreshToken()));
        item.addProperty("tokenExpiry", account.tokenExpiry());
        item.addProperty("authServerUrl", account.authServerUrl());
        item.addProperty("defaultAccount", account.defaultAccount());
        return item;
    }

    private static AuthAccount decode(JsonObject item) {
        return new AuthAccount(AuthType.valueOf(text(item, "type", "OFFLINE")),
                text(item, "uuid", ""), text(item, "username", ""),
                text(item, "displayName", ""), decrypt(item, "accessToken"),
                decrypt(item, "refreshToken"), number(item, "tokenExpiry"),
                text(item, "authServerUrl", ""), bool(item, "defaultAccount"));
    }

    private static AuthAccount withDefault(AuthAccount account, boolean selected) {
        return new AuthAccount(account.type(), account.uuid(), account.username(), account.displayName(),
                account.accessToken(), account.refreshToken(), account.tokenExpiry(),
                account.authServerUrl(), selected);
    }

    private static String decrypt(JsonObject item, String key) {
        String encrypted = text(item, key, "");
        return encrypted.isBlank() ? "" : CryptoUtil.decrypt(encrypted);
    }

    private static String text(JsonObject item, String key, String fallback) {
        return item.has(key) && !item.get(key).isJsonNull() ? item.get(key).getAsString() : fallback;
    }

    private static long number(JsonObject item, String key) {
        return item.has(key) ? item.get(key).getAsLong() : 0;
    }

    private static boolean bool(JsonObject item, String key) {
        return item.has(key) && item.get(key).getAsBoolean();
    }
}
