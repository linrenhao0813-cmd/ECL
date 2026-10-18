package com.ecl.modrinth.download;

import com.ecl.util.FileUtil;
import com.ecl.modrinth.model.ContentProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthDownloaderTest {
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
    void existingFileReuseDependsOnSha1Match(@TempDir Path tempDir) throws Exception {
        File existing = tempDir.resolve("existing.zip").toFile();
        Files.writeString(existing.toPath(), "same content");
        String sha1 = FileUtil.sha1(existing);

        // No expected hash: existing executable content must not be trusted.
        assertFalse(ModrinthDownloader.existingFileSatisfies(existing, null));
        // Matching hash: reuse.
        assertTrue(ModrinthDownloader.existingFileSatisfies(existing, sha1));
        // Mismatching hash: must be overwritten.
        assertFalse(ModrinthDownloader.existingFileSatisfies(
                existing, "0000000000000000000000000000000000000000"));
        // Missing file: always download.
        assertFalse(ModrinthDownloader.existingFileSatisfies(
                tempDir.resolve("absent.zip").toFile(), null));
    }
}
