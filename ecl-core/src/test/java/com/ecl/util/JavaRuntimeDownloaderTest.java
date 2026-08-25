package com.ecl.util;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaRuntimeDownloaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void runtimeExtractionRejectsExpansionBeyondItsBudget() throws Exception {
        Path archive = temporaryDirectory.resolve("runtime.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("runtime/bin/java.exe"));
            output.write(new byte[2048]);
            output.closeEntry();
        }
        Path destination = temporaryDirectory.resolve("runtime");

        assertThrows(IOException.class, () -> JavaRuntimeDownloader.extractZip(
                archive, destination, new ZipUtil.ExtractionLimits(1024, 1024, 10, 500.0)));
        assertFalse(Files.exists(destination.resolve("runtime/bin/java.exe")));
    }

    @Test
    void packageMetadataRequiresHttpsChecksumAndBoundedSize() throws Exception {
        String checksum = "a".repeat(64);
        var assets = JsonParser.parseString("[{\"binary\":{\"package\":{"
                + "\"link\":\"https://example.invalid/runtime.zip\","
                + "\"checksum\":\"" + checksum + "\",\"size\":1024}}}]")
                .getAsJsonArray();

        JavaRuntimeDownloader.PackageInfo selected = JavaRuntimeDownloader.resolvePackage(assets);

        assertEquals("https://example.invalid/runtime.zip", selected.url());
        assertEquals(checksum, selected.sha256());
        assertEquals(1024, selected.size());
    }

    @Test
    void sha256VerificationAcceptsMatchingArchiveAndRejectsMismatch() throws Exception {
        Path archive = temporaryDirectory.resolve("runtime.zip");
        Files.writeString(archive, "runtime-content");
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("runtime-content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        JavaRuntimeDownloader.verifySha256(archive, expected);

        assertThrows(IOException.class,
                () -> JavaRuntimeDownloader.verifySha256(archive, "0".repeat(64)));
    }
}
