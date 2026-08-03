package com.ecl.download;

import com.ecl.util.FileUtil;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDownloaderTest {
    @Test
    void existingFilesAreVerifiedByDefaultAndCanBeExplicitlyTrusted(@TempDir Path tempDir) throws IOException {
        File existing = tempDir.resolve("library.jar").toFile();
        Files.writeString(existing.toPath(), "actual contents");

        try (GameDownloader downloader = new GameDownloader()) {
            assertTrue(downloader.needsDownload(existing, "0000000000000000000000000000000000000000"));
            assertFalse(downloader.needsDownload(existing, FileUtil.sha1(existing)));
            downloader.setVerifyExistingFiles(false);
            assertFalse(downloader.needsDownload(existing, "0000000000000000000000000000000000000000"));
            assertTrue(downloader.needsDownload(tempDir.resolve("missing.jar").toFile(), null));
        }
    }

    @Test
    void nativeClassifierUsesMetadataTemplateWithoutSplittingClassifierStrings() {
        JsonObject library = new JsonObject();
        JsonObject natives = new JsonObject();
        natives.addProperty("windows", "natives-windows-${arch}");
        library.add("natives", natives);

        assertEquals("natives-windows-64",
                GameDownloader.nativeClassifierKey(library, "windows", "64"));
        assertEquals("natives-linux",
                GameDownloader.nativeClassifierKey(new JsonObject(), "linux", "64"));
    }
}
