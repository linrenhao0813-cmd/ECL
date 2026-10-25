package com.ecl.modrinth.download;

import com.ecl.util.FileUtil;
import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthDownloaderTest {
    @Test
    void missingRequiredDependencyFailsWithoutChangingExistingFiles(@TempDir Path directory) throws Exception {
        Path primary = Files.writeString(directory.resolve("primary.jar"), "verified primary");
        ModFile file = new ModFile(URI.create("https://cdn.modrinth.com/primary.jar"), "primary.jar",
                Map.of("sha1", FileUtil.sha1(primary.toFile())), true, Files.size(primary), null);
        TestFixtures.FakeApi api = new TestFixtures.FakeApi();
        api.versions.put("primary-version", new ModVersion("primary-version", "primary", "Primary", "1", "release",
                false, "listed", List.of("1.21.1"), List.of("fabric"), Instant.EPOCH, "", List.of(file),
                List.of(new ModDependency(null, "missing-project", null, DependencyType.REQUIRED))));

        IOException failure = assertThrows(IOException.class, () -> new ModrinthDownloader(api).downloadVersion(
                new ContentProject("primary", "primary", "Primary", "Author", "", 0, 0),
                new ContentVersion("primary-version", "Primary", "1", "release"),
                "1.21.1", "fabric", directory.toFile(), true, null, ".jar"));

        assertTrue(failure.getMessage().contains("missing-project"));
        assertEquals("verified primary", Files.readString(primary));
        try (var transactions = Files.list(directory.resolve(".ecl-mod-transactions"))) {
            assertTrue(transactions.noneMatch(Files::isDirectory));
        }
    }

    @Test
    void contentProjectRetainsCoverUrl() {
        ContentProject project = new ContentProject(
                "project", "project", "Project", "Author", "Description",
                "https://cdn.modrinth.com/data/project/icon.png", 12, 3);

        assertEquals("https://cdn.modrinth.com/data/project/icon.png", project.getIconUrl());
        assertEquals("Author", project.getAuthor());
    }

    @Test
    void detectsCircularDependencyChains() {
        Deque<String> path = new ArrayDeque<>();
        path.add("version-a");
        path.add("version-b");

        IOException error = assertThrows(IOException.class,
                () -> ModrinthDownloader.ensureNoDependencyCycle(path, "version-a"));

        assertTrue(error.getMessage().contains("version-a -> version-b -> version-a"));
    }

    @Test
    void dependencyCycleStartsAtRepeatedVersionAndLeavesPathIntact() {
        Deque<String> path = new ArrayDeque<>(List.of("root", "version-a", "version-b"));

        IOException error = assertThrows(IOException.class,
                () -> ModrinthDownloader.ensureNoDependencyCycle(path, "version-a"));

        assertEquals("Detected circular Modrinth dependency chain: version-a -> version-b -> version-a",
                error.getMessage());
        assertEquals(List.of("root", "version-a", "version-b"), List.copyOf(path));
        assertDoesNotThrow(
                () -> ModrinthDownloader.ensureNoDependencyCycle(path, "new-version"));
    }

    @Test
    void shaderAndResourcePackDownloadsOnlyAcceptZipFiles() {
        assertTrue(ModrinthFileSelector.isAllowedFilename("complementary-shaders.zip",
                ".zip"));
        assertFalse(ModrinthFileSelector.isAllowedFilename("shader-pack.jar", ".zip"));
        assertFalse(ModrinthFileSelector.isAllowedFilename("no-extension", ".zip"));

        assertTrue(ModrinthFileSelector.isAllowedFilename("faithful-32x.zip", ".zip"));
        assertFalse(ModrinthFileSelector.isAllowedFilename("resourcepack.jar", ".zip"));

        assertTrue(ModrinthFileSelector.isAllowedFilename("sodium.jar", ".jar"));
        assertTrue(ModrinthFileSelector.isAllowedFilename("pack.zip", ".jar", ".zip"));
    }

    @Test
    void existingFileReuseRequiresSizeAndHashMatch(@TempDir Path tempDir) throws Exception {
        File existing = tempDir.resolve("existing.zip").toFile();
        Files.writeString(existing.toPath(), "same content");
        ModFile expected = new ModFile(URI.create("https://cdn.modrinth.com/existing.zip"),
                existing.getName(), Map.of("sha1", FileUtil.sha1(existing)), true, existing.length(), null);

        assertTrue(ModrinthFileSelector.existingFileSatisfies(existing, expected));
        assertFalse(ModrinthFileSelector.existingFileSatisfies(existing,
                new ModFile(expected.url(), expected.fileName(), Map.of(), true, expected.size(), null)));

        Files.writeString(existing.toPath(), "evil content");
        assertEquals(expected.size(), existing.length());
        assertFalse(ModrinthFileSelector.existingFileSatisfies(existing, expected));
        Files.writeString(existing.toPath(), "short");
        assertFalse(ModrinthFileSelector.existingFileSatisfies(existing, expected));
        assertFalse(ModrinthFileSelector.existingFileSatisfies(
                tempDir.resolve("absent.zip").toFile(), expected));
    }
}
