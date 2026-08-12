package com.ecl.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticBundleServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsUsefulFilesWithoutCredentials() throws Exception {
        Path base = temporaryDirectory.resolve("base");
        Path game = temporaryDirectory.resolve("game");
        Files.createDirectories(base.resolve("logs"));
        Files.createDirectories(game.resolve("logs"));
        Files.writeString(base.resolve("settings.json"),
                "{\"username\":\"Alex\",\"accessToken\":\"top-secret-token\"}");
        Files.writeString(base.resolve("logs/ecl.log"), "Authorization: Bearer abc.def.ghi");
        Files.writeString(base.resolve("logs/headers.log"),
                "Authorization: Basic dXNlcjpwYXNz\nCookie: SID=cookie-secret");
        Files.writeString(game.resolve("logs/latest.log"),
                "password=hunter2 game started --accessToken cli-secret "
                        + "[\"--session\",\"array-secret\"]");

        Path archive = new DiagnosticBundleService().export(
                temporaryDirectory.resolve("diagnostics.zip"), base, game);
        StringBuilder contents = new StringBuilder();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            zip.stream().forEach(entry -> {
                try {
                    contents.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
        }

        assertTrue(contents.toString().contains("<redacted>"));
        assertFalse(contents.toString().contains("top-secret-token"));
        assertFalse(contents.toString().contains("abc.def.ghi"));
        assertFalse(contents.toString().contains("hunter2"));
        assertFalse(contents.toString().contains("dXNlcjpwYXNz"));
        assertFalse(contents.toString().contains("cookie-secret"));
        assertFalse(contents.toString().contains("cli-secret"));
        assertFalse(contents.toString().contains("array-secret"));
    }
}
