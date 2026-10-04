package com.ecl.auth.offline;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/** Owns the ephemeral key material used to sign authlib texture properties. */
final class OfflineSkinTextureSigner {
    private final KeyPair keyPair = generateKeyPair();

    String publicKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    String sign(String data) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(keyPair.getPrivate(), new SecureRandom());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException("Cannot sign texture property", failure);
        }
    }

    static String sha1Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 not available", impossible);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("RSA not available", impossible);
        }
    }
}
