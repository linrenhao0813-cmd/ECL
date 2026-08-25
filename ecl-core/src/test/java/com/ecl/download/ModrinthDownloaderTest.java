package com.ecl.download;

import com.ecl.util.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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
        ModrinthDownloader.Project project = new ModrinthDownloader.Project(
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
    void shaderAndResourcePackDownloadsOnlyAcceptZipFiles() throws Exception {
        ModrinthDownloader downloader = new ModrinthDownloader();
        Method method = ModrinthDownloader.class.getDeclaredMethod(
                "isAllowedFilename", String.class, String[].class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(downloader, "complementary-shaders.zip",
                (Object) new String[]{".zip"}));
        assertFalse((boolean) method.invoke(downloader, "shader-pack.jar",
                (Object) new String[]{".zip"}));
        assertFalse((boolean) method.invoke(downloader, "no-extension",
                (Object) new String[]{".zip"}));

        assertTrue((boolean) method.invoke(downloader, "faithful-32x.zip",
                (Object) new String[]{".zip"}));
        assertFalse((boolean) method.invoke(downloader, "resourcepack.jar",
                (Object) new String[]{".zip"}));

        assertTrue((boolean) method.invoke(downloader, "sodium.jar",
                (Object) new String[]{".jar"}));
        assertTrue((boolean) method.invoke(downloader, "pack.zip",
                (Object) new String[]{".jar", ".zip"}));
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
