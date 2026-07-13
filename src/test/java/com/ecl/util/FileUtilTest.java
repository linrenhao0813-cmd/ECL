package com.ecl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilTest {
    @TempDir Path tempDir;

    @Test
    void calculatesAndVerifiesSha1() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", FileUtil.sha1(file.toFile()));
        assertTrue(FileUtil.verifySha1(file.toFile(), "A9993E364706816ABA3E25717850C26C9CD0D89D"));
    }
}
