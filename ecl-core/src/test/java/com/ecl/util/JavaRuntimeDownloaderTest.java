package com.ecl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
