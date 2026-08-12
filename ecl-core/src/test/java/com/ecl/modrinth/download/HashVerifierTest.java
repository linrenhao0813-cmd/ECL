package com.ecl.modrinth.download;

import com.ecl.modrinth.api.HashMismatchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HashVerifierTest {
    @TempDir Path temp;
    private final HashVerifier verifier = new HashVerifier();

    @Test
    void calculatesAndVerifiesSha1AndSha512() throws Exception {
        Path file = Files.writeString(temp.resolve("data.bin"), "hello");
        HashVerifier.HashResult hashes = verifier.calculate(file);
        assertEquals(digest("SHA-1", "hello".getBytes(StandardCharsets.UTF_8)), hashes.sha1());
        assertEquals(digest("SHA-512", "hello".getBytes(StandardCharsets.UTF_8)), hashes.sha512());
        assertEquals(hashes, verifier.verify(file,
                Map.of("sha1", hashes.sha1(), "sha512", hashes.sha512())));
    }

    @Test
    void rejectsWrongHash() throws Exception {
        Path file = Files.writeString(temp.resolve("bad.bin"), "hello");
        HashMismatchException error = assertThrows(
                HashMismatchException.class,
                () -> verifier.verify(file, Map.of("sha512", "00")));
        assertEquals(file.toAbsolutePath().normalize(), error.file());
        assertEquals("SHA-512", error.algorithm());
        assertEquals("00", error.expected());
        assertFalse(error.actual().isBlank());
    }

    @Test
    void handlesEmptyAndLargeFilesUsingStreamingReads() throws Exception {
        Path empty = Files.createFile(temp.resolve("empty.bin"));
        assertEquals(digest("SHA-1", new byte[0]), verifier.calculate(empty).sha1());
        byte[] data = new byte[3 * 1024 * 1024 + 17];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) (index * 31);
        }
        Path large = Files.write(temp.resolve("large.bin"), data);
        assertEquals(digest("SHA-512", data), verifier.calculate(large).sha512());
    }

    private static String digest(String algorithm, byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }
}
