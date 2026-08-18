package com.ecl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void safeResolveKeepsPathsInsideRoot() throws Exception {
        File root = tempDir.toFile();
        assertEquals(root.toPath().resolve("a/b/c.jar").normalize(),
                FileUtil.safeResolveUnder(root, "a/b/c.jar").toPath());
        assertEquals(root.toPath().resolve("x/y").normalize(),
                FileUtil.safeResolveUnder(root, "x\\y").toPath());
    }

    @Test
    void safeResolveRejectsParentTraversal() {
        File root = tempDir.toFile();
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, "../evil.jar"));
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, "a/../../evil.jar"));
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, "..\\evil.jar"));
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, "a\\..\\..\\evil.jar"));
    }

    @Test
    void safeResolveRejectsAbsoluteAndBlankPaths() {
        File root = tempDir.toFile();
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, "/absolute.jar"));
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, ""));
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, "   "));
        assertThrows(IOException.class, () -> FileUtil.safeResolveUnder(root, null));
    }

    @Test
    void normalizesArmAndX64NativeArchitecturesWithoutConflatingThem() {
        assertEquals("arm64", FileUtil.nativeArchitecture("aarch64"));
        assertEquals("arm64", FileUtil.nativeArchitecture("ARM64"));
        assertEquals("x86_64", FileUtil.nativeArchitecture("amd64"));
        assertEquals("x86_64", FileUtil.nativeArchitecture("x86_64"));
        assertEquals("x86", FileUtil.nativeArchitecture("x86"));
    }

    @Test
    void validatesVersionIdsAsSafePlatformIndependentPathSegments() throws Exception {
        FileUtil.requireSafeVersionId("1.21.4");
        FileUtil.requireSafeVersionId("3D Shareware v1.34");

        assertThrows(IOException.class, () -> FileUtil.requireSafeVersionId("../outside"));
        assertThrows(IOException.class, () -> FileUtil.requireSafeVersionId("..\\outside"));
        assertThrows(IOException.class, () -> FileUtil.requireSafeVersionId("CON"));
        assertThrows(IOException.class, () -> FileUtil.requireSafeVersionId("version."));
        assertThrows(IOException.class, () -> FileUtil.requireSafeVersionId("version\u0000"));
    }
}
