package com.ecl.curseforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Computes the whitespace-normalized MurmurHash2 fingerprint used by CurseForge. */
public final class CurseForgeFingerprint {
    private static final int SEED = 1;
    private static final int MULTIPLIER = 0x5bd1e995;

    private CurseForgeFingerprint() {
    }

    public static long calculate(Path file) throws IOException {
        long filteredLength = countFingerprintBytes(file);
        if (filteredLength > Integer.MAX_VALUE) {
            throw new IOException("CurseForge 指纹文件过大: " + file);
        }
        int hash = SEED ^ (int) filteredLength;
        byte[] buffer = new byte[64 * 1024];
        int packed = 0;
        int packedBytes = 0;
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    int value = buffer[index] & 0xff;
                    if (isWhitespace(value)) {
                        continue;
                    }
                    packed |= value << (packedBytes * 8);
                    if (++packedBytes == 4) {
                        hash = mix(hash, packed);
                        packed = 0;
                        packedBytes = 0;
                    }
                }
            }
        }
        if (packedBytes > 0) {
            hash ^= packed;
            hash *= MULTIPLIER;
        }
        hash ^= hash >>> 13;
        hash *= MULTIPLIER;
        hash ^= hash >>> 15;
        return Integer.toUnsignedLong(hash);
    }

    private static long countFingerprintBytes(Path file) throws IOException {
        long count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    if (!isWhitespace(buffer[index] & 0xff)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int mix(int hash, int block) {
        int value = block * MULTIPLIER;
        value ^= value >>> 24;
        value *= MULTIPLIER;
        hash *= MULTIPLIER;
        return hash ^ value;
    }

    private static boolean isWhitespace(int value) {
        return value == 9 || value == 10 || value == 13 || value == 32;
    }
}
