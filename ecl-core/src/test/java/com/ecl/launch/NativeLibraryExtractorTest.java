package com.ecl.launch;

import com.ecl.game.VersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLibraryExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractJarCopiesEntriesWithinBudget() throws Exception {
        Path jar = writeJar("normal.jar", Map.of("bin/example.dll", new byte[]{1, 2, 3}));
        Path output = Files.createDirectories(tempDir.resolve("out"));
        NativeLibraryExtractor.ExtractionBudget budget = budget(20, 10, 3);

        NativeLibraryExtractor.extractJar(jar.toFile(), output.toFile(), budget);

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(output.resolve("bin/example.dll")));
    }

    @Test
    void rejectsAndRemovesAnOversizedNativeEntry() throws Exception {
        Path jar = writeJar("single-limit.jar", Map.of("large.dll", new byte[11]));
        Path output = Files.createDirectories(tempDir.resolve("out"));
        NativeLibraryExtractor.ExtractionBudget budget = budget(100, 10, 3);

        assertThrows(IOException.class,
                () -> NativeLibraryExtractor.extractJar(jar.toFile(), output.toFile(), budget));
        assertFalse(Files.exists(output.resolve("large.dll")));
    }

    @Test
    void enforcesTotalNativeSizeAcrossMultipleJars() throws Exception {
        Path firstJar = writeJar("total-first.jar", Map.of("first.dll", new byte[8]));
        Path secondJar = writeJar("total-second.jar", Map.of("second.dll", new byte[8]));
        Path output = Files.createDirectories(tempDir.resolve("out"));
        NativeLibraryExtractor.ExtractionBudget budget = budget(12, 10, 4);

        NativeLibraryExtractor.extractJar(firstJar.toFile(), output.toFile(), budget);
        assertThrows(IOException.class,
                () -> NativeLibraryExtractor.extractJar(secondJar.toFile(), output.toFile(), budget));

        assertTrue(Files.exists(output.resolve("first.dll")));
        assertFalse(Files.exists(output.resolve("second.dll")));
    }

    @Test
    void enforcesNativeEntryCountAcrossMultipleJars() throws Exception {
        Path firstJar = writeJar("count-first.jar", Map.of("first.dll", new byte[]{1}));
        Path secondJar = writeJar("count-second.jar", Map.of("second.dll", new byte[]{2}));
        Path output = Files.createDirectories(tempDir.resolve("out"));
        NativeLibraryExtractor.ExtractionBudget budget = budget(100, 50, 1);

        NativeLibraryExtractor.extractJar(firstJar.toFile(), output.toFile(), budget);
        assertThrows(IOException.class,
                () -> NativeLibraryExtractor.extractJar(secondJar.toFile(), output.toFile(), budget));
        assertFalse(Files.exists(output.resolve("second.dll")));
    }

    @Test
    void extractFromTypedMetadataStagesNativesIdempotently() throws Exception {
        Path versionsDir = Files.createDirectories(tempDir.resolve("versions"));
        Path librariesDir = Files.createDirectories(tempDir.resolve("libs"));
        Path assetsDir = Files.createDirectories(tempDir.resolve("assets"));
        String nativeOs = "windows";
        String nativeClassifier = "natives-" + nativeOs;
        Path nativeJar = librariesDir.resolve("lwjgl/" + nativeClassifier + ".jar");
        Files.createDirectories(nativeJar.getParent());
        writeJarTo(nativeJar, nativeClassifier + ".jar", Map.of("lwjgl.dll", new byte[]{9, 9, 9}));

        String versionJson = """
                {"mainClass":"net.minecraft.client.main.Main",
                 "libraries":[{"name":"org.lwjgl:lwjgl:3.3.1",
                   "natives":{"%s":"%s"},
                   "downloads":{
                     "artifact":{"url":"https://x/lwjgl.jar","path":"lwjgl/3.3.1.jar"},
                     "classifiers":{"%s":{"url":"https://x/n.jar",
                       "path":"lwjgl/%s.jar"}}}}]}
                """.formatted(nativeOs, nativeClassifier, nativeClassifier, nativeClassifier);
        Path versionDir = Files.createDirectories(versionsDir.resolve("1.21"));
        Files.writeString(versionDir.resolve("1.21.json"), versionJson, StandardCharsets.UTF_8);
        VersionRepository repository = new VersionRepository(versionsDir.toFile());
        LaunchEnvironment environment = new LaunchEnvironment(versionsDir.toFile(),
                librariesDir.toFile(), assetsDir.toFile(), "ECL", "1.0.0");

        NativeLibraryExtractor.extract(repository.resolve("1.21"), environment, "1.21");
        Path staged = versionsDir.resolve("1.21/natives/lwjgl.dll");
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(staged));

        long firstModified = Files.getLastModifiedTime(staged).toMillis();
        Thread.sleep(5);
        NativeLibraryExtractor.extract(repository.resolve("1.21"), environment, "1.21");
        assertEquals(firstModified, Files.getLastModifiedTime(staged).toMillis(),
                "unchanged sources must not re-extract");
    }

    @Test
    void changedSourceJarTriggersReExtraction() throws Exception {
        Path versionsDir = Files.createDirectories(tempDir.resolve("versions"));
        Path librariesDir = Files.createDirectories(tempDir.resolve("libs"));
        Path assetsDir = Files.createDirectories(tempDir.resolve("assets"));
        String nativeOs = "windows";
        String nativeClassifier = "natives-" + nativeOs;
        Path nativeJar = librariesDir.resolve(nativeClassifier + ".jar");
        writeJarTo(nativeJar, nativeClassifier + ".jar", Map.of("native.dll", new byte[]{1}));

        String versionJson = """
                {"mainClass":"net.minecraft.client.main.Main",
                 "libraries":[{"name":"org.x:natives:1.0",
                   "natives":{"%s":"%s"},
                   "downloads":{"classifiers":{"%s":{"url":"https://x/n.jar",
                     "path":"%s.jar"}}}}]}
                """.formatted(nativeOs, nativeClassifier, nativeClassifier, nativeClassifier);
        Path versionDir = Files.createDirectories(versionsDir.resolve("1.21"));
        Files.writeString(versionDir.resolve("1.21.json"), versionJson, StandardCharsets.UTF_8);
        VersionRepository repository = new VersionRepository(versionsDir.toFile());
        LaunchEnvironment environment = new LaunchEnvironment(versionsDir.toFile(),
                librariesDir.toFile(), assetsDir.toFile(), "ECL", "1.0.0");
        NativeLibraryExtractor.extract(repository.resolve("1.21"), environment, "1.21");
        Path staged = versionsDir.resolve("1.21/natives/native.dll");
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(staged));

        writeJarTo(nativeJar, nativeClassifier + ".jar", Map.of("native.dll", new byte[]{2}));
        NativeLibraryExtractor.extract(repository.resolve("1.21"), environment, "1.21");
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(staged));
    }

    @Test
    void markerDetectsCorruptedExtractedFilesWithIdenticalMetadata() throws Exception {
        Path nativesDirectory = Files.createDirectories(tempDir.resolve("natives"));
        Path extractedLibrary = nativesDirectory.resolve("example.dll");
        Files.writeString(extractedLibrary, "abc", StandardCharsets.UTF_8);

        String first = NativeLibraryExtractor.buildMarker("source", nativesDirectory);
        Files.writeString(extractedLibrary, "xyz", StandardCharsets.UTF_8);
        String second = NativeLibraryExtractor.buildMarker("source", nativesDirectory);

        assertNotEquals(first, second);
    }

    @Test
    void extractJarRejectsEntriesThatEscapeTheDestination() throws Exception {
        Path jar = writeJar("slip.jar", Map.of("../evil.dll", new byte[]{1}));
        Path output = Files.createDirectories(tempDir.resolve("out"));
        NativeLibraryExtractor.ExtractionBudget budget = budget(100, 50, 3);

        assertThrows(IOException.class,
                () -> NativeLibraryExtractor.extractJar(jar.toFile(), output.toFile(), budget));
        assertFalse(Files.exists(tempDir.resolve("evil.dll")));
        assertFalse(Files.exists(output.resolve("evil.dll")));
    }

    @Test
    void sourceFingerprintChangeShowsInMarker() throws Exception {
        Path nativesDirectory = Files.createDirectories(tempDir.resolve("natives"));
        String first = NativeLibraryExtractor.buildMarker("source-v1", nativesDirectory);
        String second = NativeLibraryExtractor.buildMarker("source-v2", nativesDirectory);
        assertNotEquals(first, second);
        assertTrue(first.startsWith("sources\n"));
        assertTrue(first.contains("extracted\n"));
    }

    private static NativeLibraryExtractor.ExtractionBudget budget(long total, long single, int entries) {
        return new NativeLibraryExtractor.ExtractionBudget(
                new NativeLibraryExtractor.ExtractionLimits(total, single, entries));
    }

    private Path writeJar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = tempDir.resolve(name);
        writeJarTo(jar, name, entries);
        return jar;
    }

    private static void writeJarTo(Path jar, String name, Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> orderedEntries = new LinkedHashMap<>(entries);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : orderedEntries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
