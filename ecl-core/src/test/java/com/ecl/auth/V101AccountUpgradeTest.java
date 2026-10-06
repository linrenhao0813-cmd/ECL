package com.ecl.auth;

import com.ecl.util.CryptoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.jna.platform.win32.Crypt32Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Recreates the baseline account/key encodings independently, with synthetic credentials only. */
class V101AccountUpgradeTest {
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    @Test
    void reloadsBaselineEncryptedAccountsAndRetainsRetiredAccountType(@TempDir Path root) throws Exception {
        String previousKey = System.getProperty("ecl.crypto.keyFile");
        String previousMachine = System.getProperty("ecl.crypto.machineId");
        Path keyFile = root.resolve(".secret.key");
        System.setProperty("ecl.crypto.keyFile", keyFile.toString());
        System.setProperty("ecl.crypto.machineId", "synthetic-v101-machine");
        CryptoUtil.resetKeyCache();
        try {
            byte[] key = new byte[32];
            java.util.Arrays.fill(key, (byte) 42);
            Files.write(keyFile, baselineKeyEncoding(key));
            byte[] keyBytes = Files.readAllBytes(keyFile);
            JsonArray accounts = new JsonArray();
            accounts.add(account("MICROSOFT", "ms-id", key, true));
            accounts.add(account("YGGDRASIL", "legacy-id", key, false));
            Path file = root.resolve("accounts.json");
            Files.writeString(file, accounts.toString());
            byte[] original = Files.readAllBytes(file);
            DefaultAccountService reopened = new DefaultAccountService(file);
            assertEquals(2, reopened.list().size());
            AuthAccount selected = reopened.defaultAccount().orElseThrow();
            assertEquals("MICROSOFT:ms-id", selected.identity());
            assertEquals("synthetic-access", selected.accessToken());
            assertEquals("synthetic-refresh", selected.refreshToken());
            assertEquals(AuthType.YGGDRASIL, reopened.list().get(1).type());
            assertArrayEquals(original, Files.readAllBytes(file), "opening accounts must not rewrite them");
            CryptoUtil.resetKeyCache();
            assertEquals("synthetic-refresh", new DefaultAccountService(file).defaultAccount().orElseThrow().refreshToken());
            reopened.addOffline("Steve");
            CryptoUtil.resetKeyCache();
            assertEquals(3, new DefaultAccountService(file).list().size());
            assertFalse(Files.readString(file).contains("synthetic-refresh"));
            assertArrayEquals(keyBytes, Files.readAllBytes(keyFile));
        } finally {
            CryptoUtil.resetKeyCache();
            restoreProperty("ecl.crypto.keyFile", previousKey);
            restoreProperty("ecl.crypto.machineId", previousMachine);
        }
    }

    private static JsonObject account(String type, String id, byte[] key, boolean selected) throws Exception {
        JsonObject account = new JsonObject();
        account.addProperty("type", type);
        account.addProperty("uuid", id);
        account.addProperty("username", "LegacyPlayer");
        account.addProperty("displayName", "LegacyPlayer");
        account.addProperty("accessToken", Base64.getEncoder().encodeToString(encrypt(key, "synthetic-access".getBytes(StandardCharsets.UTF_8))));
        account.addProperty("refreshToken", Base64.getEncoder().encodeToString(encrypt(key, "synthetic-refresh".getBytes(StandardCharsets.UTF_8))));
        account.addProperty("tokenExpiry", 1234);
        account.addProperty("authServerUrl", "");
        account.addProperty("defaultAccount", selected);
        return account;
    }

    private static byte[] baselineKeyEncoding(byte[] key) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            byte[] prefix = "ECL-DPAPI-1\n".getBytes(StandardCharsets.US_ASCII);
            byte[] wrapped = Crypt32Util.cryptProtectData(key);
            return ByteBuffer.allocate(prefix.length + wrapped.length).put(prefix).put(wrapped).array();
        }
        byte[] prefix = "ECL-LOCAL-3\n".getBytes(StandardCharsets.US_ASCII);
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) 7);
        String material = "ECL-local-key-wrapper-v3\n" + System.getProperty("user.name", "") + '\n'
                + System.getProperty("user.home", "") + '\n' + System.getProperty("os.name", "") + '\n'
                + HexFormat.of().formatHex("synthetic-v101-machine".getBytes(StandardCharsets.UTF_8));
        PBEKeySpec spec = new PBEKeySpec(material.toCharArray(), salt, 210_000, 256);
        byte[] wrappingKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        byte[] wrapped = encrypt(wrappingKey, key);
        return ByteBuffer.allocate(prefix.length + salt.length + wrapped.length).put(prefix).put(salt).put(wrapped).array();
    }

    private static byte[] encrypt(byte[] key, byte[] plaintext) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
