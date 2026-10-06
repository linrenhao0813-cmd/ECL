package com.ecl.modrinth.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MrpackOverrideExtractorTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsEntriesAndSharesBudgetAcrossOverridePrefixes() throws Exception {
        Path archive = createArchive(
                "overrides/config/example.txt", "enabled=true",
                "client-overrides/options.txt", "fov:0.5");
        Path instanceRoot = tempDir.resolve("instance");
        MrpackOverrideExtractor.ExtractionBudget budget =
                new MrpackOverrideExtractor.ExtractionBudget();

        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(1, MrpackOverrideExtractor.extract(
                    zip, "overrides/", instanceRoot, budget));
            assertEquals(1, MrpackOverrideExtractor.extract(
                    zip, "client-overrides/", instanceRoot, budget));
        }

        assertEquals("enabled=true",
                Files.readString(instanceRoot.resolve("config/example.txt")));
        assertEquals("fov:0.5",
                Files.readString(instanceRoot.resolve("options.txt")));
    }

    @Test
    void clientOverridesReplaceCommonLayerWhileSharingSafetyBudget() throws Exception {
        Path archive = createArchive("overrides/options.txt", "common",
                "client-overrides/options.txt", "client");
        Path instanceRoot = tempDir.resolve("instance");
        MrpackOverrideExtractor.ExtractionBudget budget = new MrpackOverrideExtractor.ExtractionBudget();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            MrpackOverrideExtractor.extract(zip, "overrides/", instanceRoot, budget);
            MrpackOverrideExtractor.extract(zip, "client-overrides/", instanceRoot, budget);
        }
        assertEquals("client", Files.readString(instanceRoot.resolve("options.txt")));
    }

    @Test
    void rejectsAliasesOfOneTargetWithinTheSameLayer() throws Exception {
        Path archive = createArchive("overrides/options.txt", "first", "overrides/./options.txt", "second");
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            assertThrows(IOException.class, () -> MrpackOverrideExtractor.extract(zip, "overrides/",
                    tempDir.resolve("instance"), new MrpackOverrideExtractor.ExtractionBudget()));
        }
    }

    @Test
    void rejectsEntriesThatEscapeTheInstanceDirectory() throws Exception {
        Path archive = createArchive("overrides/../outside.txt", "must not escape");
        Path instanceRoot = tempDir.resolve("instance");

        IOException error;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            error = assertThrows(IOException.class, () -> MrpackOverrideExtractor.extract(
                    zip, "overrides/", instanceRoot,
                    new MrpackOverrideExtractor.ExtractionBudget()));
        }

        assertTrue(error.getMessage().contains("安全") || error.getMessage().contains("outside"));
        assertTrue(!Files.exists(tempDir.resolve("outside.txt")));
    }

    private Path createArchive(String firstName, String firstContent,
                               String... additionalEntries) throws IOException {
        Path archive = tempDir.resolve("overrides.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeEntry(zip, firstName, firstContent);
            for (int i = 0; i < additionalEntries.length; i += 2) {
                writeEntry(zip, additionalEntries[i], additionalEntries[i + 1]);
            }
        }
        return archive;
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
