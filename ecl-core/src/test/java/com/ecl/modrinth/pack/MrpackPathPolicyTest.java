package com.ecl.modrinth.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MrpackPathPolicyTest {
    @TempDir
    Path tempDirectory;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsDirectoryJunctions() throws Exception {
        Path root = Files.createDirectories(tempDirectory.resolve("instance"));
        Path external = Files.createDirectories(tempDirectory.resolve("external"));
        Path junction = root.resolve("mods");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.Charset.defaultCharset());
        assertEquals(0, process.waitFor(), output);

        assertThrows(IOException.class,
                () -> MrpackPathPolicy.safeResolve(root, "mods/example.jar"));
    }
}
