package com.ecl.util;

import com.ecl.ECLConfig;
import com.sun.jna.platform.win32.Crypt32Util;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads and stores the AES key used to protect sensitive launcher data. */
final class CryptoKeyStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CryptoKeyStore.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;
    private static final byte[] DPAPI_HEADER = "ECL-DPAPI-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOCAL_HEADER_V1 = "ECL-LOCAL-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOCAL_HEADER_V2 = "ECL-LOCAL-2\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOCAL_HEADER = "ECL-LOCAL-3\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROVIDER_HEADER = "ECL-PROVIDER-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int LOCAL_KDF_SALT_LENGTH = 16;
    private static final int LOCAL_KDF_ITERATIONS = 210_000;
    private static final String LOCAL_KDF_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static volatile SecretKey cachedKey;
    private static volatile KeyProtectionProvider protectionProvider;

    private CryptoKeyStore() {
    }

    static SecretKey loadOrCreate(boolean create) throws IOException, NoSuchAlgorithmException {
        SecretKey key = cachedKey;
        if (key != null) {
            return key;
        }
        synchronized (CryptoKeyStore.class) {
            key = cachedKey;
            if (key != null) {
                return key;
            }
            File keyFile = keyFile();
            Path keyPath = keyFile.toPath().toAbsolutePath().normalize();
            Path parent = keyPath.getParent();
            if (parent == null || keyPath.getFileName() == null) {
                throw new IOException("Account encryption key has no parent directory");
            }
            Path lockPath = keyPath.resolveSibling(keyPath.getFileName() + ".lock");
            try (FileLockLease ignored = FileLockLease.tryAcquire(lockPath)) {
                if (ignored == null) {
                    throw new IOException("Account encryption key is busy in another process");
                }
                // The file must be re-read after taking the cross-process lock. Two launcher
                // processes may otherwise generate different keys on first startup.
                if (Files.isSymbolicLink(keyPath)) {
                    throw new IOException("Account encryption key must not be a symbolic link");
                }
                if (Files.isRegularFile(keyPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    byte[] stored = Files.readAllBytes(keyPath);
                    byte[] encoded = decodeStoredKey(stored);
                    if (encoded.length != KEY_SIZE / Byte.SIZE) {
                        throw new IOException("Invalid account encryption key length");
                    }
                    key = new SecretKeySpec(encoded, KEY_ALGORITHM);
                    boolean legacyProtection = !startsWith(stored, DPAPI_HEADER)
                            && !startsWith(stored, LOCAL_HEADER)
                            && !startsWith(stored, PROVIDER_HEADER);
                    if (legacyProtection) {
                        writeKeyFile(keyPath, encodeMigrationKey(encoded));
                    }
                } else {
                    if (Files.exists(keyPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Account encryption key is not a regular file");
                    }
                    if (!create) {
                        throw new IOException("Account encryption key does not exist");
                    }
                    key = generateKey();
                    byte[] encoded = key.getEncoded();
                    Files.createDirectories(parent);
                    writeKeyFile(keyPath, encodeStoredKey(encoded));
                }
            }
            cachedKey = key;
            return key;
        }
    }

    static void resetCache() {
        cachedKey = null;
    }

    static void setProtectionProvider(KeyProtectionProvider provider) {
        protectionProvider = provider;
        resetCache();
    }

    static SecretKey localWrappingKey(byte[] salt) throws GeneralSecurityException {
        if (salt == null || salt.length < LOCAL_KDF_SALT_LENGTH) {
            throw new IllegalArgumentException("Local wrapping-key salt is too short");
        }
        byte[] machineId = machineEntropy();
        String material = "ECL-local-key-wrapper-v3\n"
                + System.getProperty("user.name", "") + '\n'
                + System.getProperty("user.home", "") + '\n'
                + System.getProperty("os.name", "") + '\n'
                + HexFormat.of().formatHex(machineId);
        char[] password = material.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, LOCAL_KDF_ITERATIONS, KEY_SIZE);
        byte[] derived = null;
        try {
            derived = SecretKeyFactory.getInstance(LOCAL_KDF_ALGORITHM)
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, KEY_ALGORITHM);
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
            Arrays.fill(machineId, (byte) 0);
            if (derived != null) {
                Arrays.fill(derived, (byte) 0);
            }
        }
    }

    static SecretKey legacyLocalWrappingKeyV2() throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("ECL-local-key-wrapper-v2\n".getBytes(StandardCharsets.UTF_8));
        digest.update(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
        digest.update(System.getProperty("user.home", "").getBytes(StandardCharsets.UTF_8));
        digest.update(System.getProperty("os.name", "").getBytes(StandardCharsets.UTF_8));
        digest.update(machineEntropy());
        return new SecretKeySpec(digest.digest(), KEY_ALGORITHM);
    }

    static SecretKey legacyLocalWrappingKey() throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("ECL-local-key-wrapper-v1\n".getBytes(StandardCharsets.UTF_8));
        digest.update(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
        digest.update(System.getProperty("user.home", "").getBytes(StandardCharsets.UTF_8));
        digest.update(System.getProperty("os.name", "").getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest.digest(), KEY_ALGORITHM);
    }

    private static SecretKey generateKey() throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance(KEY_ALGORITHM);
        generator.init(KEY_SIZE, SecureRandom.getInstanceStrong());
        return generator.generateKey();
    }

    private static File keyFile() {
        String override = System.getProperty("ecl.crypto.keyFile", "");
        if (!override.isBlank()) {
            return new File(override);
        }
        return new File(ECLConfig.getBaseDir(), ".secret.key");
    }

    private static byte[] encodeStoredKey(byte[] key)
            throws IOException, NoSuchAlgorithmException {
        if (isWindows()) {
            try {
                byte[] protectedKey = Crypt32Util.cryptProtectData(key);
                ByteBuffer buffer = ByteBuffer.allocate(DPAPI_HEADER.length + protectedKey.length);
                buffer.put(DPAPI_HEADER);
                buffer.put(protectedKey);
                return buffer.array();
            } catch (RuntimeException failure) {
                throw new IOException("Unable to protect account key with Windows DPAPI", failure);
            }
        }
        KeyProtectionProvider provider = protectionProvider;
        if (provider == null) {
            throw new IOException("No secure account key protection provider is configured");
        }
        try {
            byte[] protectedKey = provider.protect(key);
            if (protectedKey == null || protectedKey.length == 0) {
                throw new IOException("Configured key protection provider returned no data");
            }
            ByteBuffer buffer = ByteBuffer.allocate(PROVIDER_HEADER.length + protectedKey.length);
            buffer.put(PROVIDER_HEADER).put(protectedKey);
            return buffer.array();
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to protect account encryption key", failure);
        }
    }

    /**
     * Keep old non-Windows installations readable when no keyring provider has been wired yet.
     * New keys never use this path; the fallback is explicitly weak, logged, and protected with
     * owner-only file permissions so it can be replaced by a user/OS-secret provider later.
     */
    private static byte[] encodeMigrationKey(byte[] key)
            throws IOException, NoSuchAlgorithmException {
        if (!isWindows() && protectionProvider == null) {
            LOGGER.warn("Migrating a legacy account key with the weak local wrapper; configure "
                    + "a KeyProtectionProvider to use an OS/user secret");
            return encodeLocalKey(key);
        }
        return encodeStoredKey(key);
    }

    private static byte[] decodeStoredKey(byte[] stored)
            throws IOException, NoSuchAlgorithmException {
        if (startsWith(stored, DPAPI_HEADER)) {
            if (!isWindows()) {
                throw new IOException("Windows DPAPI account key cannot be opened on this operating system");
            }
            try {
                return Crypt32Util.cryptUnprotectData(
                        Arrays.copyOfRange(stored, DPAPI_HEADER.length, stored.length));
            } catch (RuntimeException failure) {
                throw new IOException("Unable to unlock account key with Windows DPAPI", failure);
            }
        }
        if (startsWith(stored, PROVIDER_HEADER)) {
            KeyProtectionProvider provider = protectionProvider;
            if (provider == null) {
                throw new IOException("No configured key protection provider");
            }
            try {
                return provider.unprotect(Arrays.copyOfRange(stored, PROVIDER_HEADER.length,
                        stored.length));
            } catch (GeneralSecurityException failure) {
                throw new IOException("Unable to unlock the protected account key", failure);
            }
        }
        if (startsWith(stored, LOCAL_HEADER)) {
            return decodeLocalKey(stored);
        }
        if (startsWith(stored, LOCAL_HEADER_V2)) {
            return decodeLegacyLocalKey(stored, LOCAL_HEADER_V2, legacyLocalWrappingKeyV2());
        }
        if (startsWith(stored, LOCAL_HEADER_V1)) {
            return decodeLegacyLocalKey(stored, LOCAL_HEADER_V1, legacyLocalWrappingKey());
        }
        return stored.clone();
    }

    private static byte[] encodeLocalKey(byte[] key)
            throws IOException, NoSuchAlgorithmException {
        try {
            byte[] salt = new byte[LOCAL_KDF_SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(salt);
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, localWrappingKey(salt),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] wrapped = cipher.doFinal(key);
            ByteBuffer buffer = ByteBuffer.allocate(
                    LOCAL_HEADER.length + salt.length + iv.length + wrapped.length);
            buffer.put(LOCAL_HEADER);
            buffer.put(salt);
            buffer.put(iv);
            buffer.put(wrapped);
            return buffer.array();
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to protect account key with the local fallback", failure);
        }
    }

    private static byte[] decodeLocalKey(byte[] stored) throws IOException {
        if (stored.length < LOCAL_HEADER.length + LOCAL_KDF_SALT_LENGTH + GCM_IV_LENGTH + 1) {
            throw new IOException("Invalid locally protected account encryption key");
        }
        byte[] salt = new byte[LOCAL_KDF_SALT_LENGTH];
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored, LOCAL_HEADER.length,
                    stored.length - LOCAL_HEADER.length);
            buffer.get(salt);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] wrapped = new byte[buffer.remaining()];
            buffer.get(wrapped);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, localWrappingKey(salt),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(wrapped);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IOException("Unable to unlock the locally protected account key", failure);
        } finally {
            Arrays.fill(salt, (byte) 0);
        }
    }

    private static byte[] decodeLegacyLocalKey(
            byte[] stored, byte[] header, SecretKey wrappingKey)
            throws IOException {
        if (stored.length < header.length + GCM_IV_LENGTH + 1) {
            throw new IOException("Invalid locally protected account encryption key");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored, header.length,
                    stored.length - header.length);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] wrapped = new byte[buffer.remaining()];
            buffer.get(wrapped);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(wrapped);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IOException("Unable to unlock the locally protected account key", failure);
        }
    }

    private static byte[] machineEntropy() {
        String override = System.getProperty("ecl.crypto.machineId", "").trim();
        if (!override.isBlank()) {
            return override.getBytes(StandardCharsets.UTF_8);
        }
        for (Path candidate : List.of(Path.of("/etc/machine-id"),
                Path.of("/var/lib/dbus/machine-id"))) {
            try {
                if (Files.isRegularFile(candidate)) {
                    String machineId = Files.readString(candidate, StandardCharsets.UTF_8).trim();
                    if (!machineId.isBlank() && machineId.length() <= 4096) {
                        return machineId.getBytes(StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to network-interface identity.
            }
        }
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            interfaces.sort(Comparator.comparing(NetworkInterface::getName));
            StringBuilder hardwareIds = new StringBuilder();
            for (NetworkInterface networkInterface : interfaces) {
                byte[] address = networkInterface.getHardwareAddress();
                if (address != null && address.length > 0) {
                    hardwareIds.append(networkInterface.getName()).append('=')
                            .append(HexFormat.of().formatHex(address)).append('\n');
                }
            }
            if (!hardwareIds.isEmpty()) {
                return hardwareIds.toString().getBytes(StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall through to the host identity available to this process.
        }
        String hostname = System.getenv().getOrDefault("HOSTNAME",
                System.getenv().getOrDefault("COMPUTERNAME", "unknown-host"));
        return hostname.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private static void writeKeyFile(Path target, byte[] value) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Account encryption key has no parent directory");
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Account encryption key must not be a symbolic link");
        }
        Path temp = Files.createTempFile(parent, ".secret-key-", ".tmp");
        try {
            Files.write(temp, value);
            enforceOwnerOnlyPermissions(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            enforceOwnerOnlyPermissions(target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void enforceOwnerOnlyPermissions(Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses DPAPI; providers without POSIX permissions use their own ACL model.
        }
    }
}
