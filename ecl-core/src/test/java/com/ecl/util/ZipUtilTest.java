package com.ecl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipUtilTest {
    @TempDir
    Path temp;

    @Test
    void completionReportsActualBytesWhenSourceGrowsAfterScan() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("data"), "a".repeat(200_000));
        long[] last = new long[2];
        boolean[] changed = {false};
        var archived = ZipUtil.zipDirectories(java.util.Map.of("saves", source), temp.resolve("backup.zip"),
                (done, total, entry) -> {
                    last[0] = done;
                    last[1] = total;
                    if (!changed[0]) {
                        changed[0] = true;
                        try {
                            Files.writeString(file, "b".repeat(100_000), java.nio.file.StandardOpenOption.APPEND);
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    }
                });
        org.junit.jupiter.api.Assertions.assertEquals(300_000, archived.getFirst().size());
        org.junit.jupiter.api.Assertions.assertEquals(300_000, last[0]);
        org.junit.jupiter.api.Assertions.assertEquals(last[0], last[1]);
    }

    @Test
    void rejectsHighCompressionRatioBeforeExtraction() throws Exception {
        Path archive = temp.resolve("bomb.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("large.txt"));
            zip.write(new byte[1024 * 1024]);
            zip.closeEntry();
        }
        Path destination = temp.resolve("output");

        assertThrows(IOException.class, () -> ZipUtil.extractSafely(archive, destination, null,
                new ZipUtil.ExtractionLimits(2_000_000, 2_000_000, 10, 10.0)));
        assertFalse(Files.exists(destination.resolve("large.txt")));
    }

    @Test
    void enforcesEntryCountBudget() throws Exception {
        Path archive = temp.resolve("many.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < 3; i++) {
                zip.putNextEntry(new ZipEntry("file-" + i));
                zip.write(i);
                zip.closeEntry();
            }
        }

        IOException failure = assertThrows(IOException.class,
                () -> ZipUtil.validateArchive(archive,
                        new ZipUtil.ExtractionLimits(100, 100, 2, 100.0)));
        assertTrue(failure.getMessage().contains("entry count"));
    }

    @Test
    void rejectsEntriesThatEscapeTheDestination() throws Exception {
        Path archive = temp.resolve("slip.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../evil.txt"));
            zip.write("nope".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path destination = Files.createDirectories(temp.resolve("output"));
        assertThrows(IOException.class, () -> ZipUtil.extractSafely(archive, destination, null));
        assertFalse(Files.exists(temp.resolve("evil.txt")));
    }
}
