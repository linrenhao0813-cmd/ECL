package com.ecl.download;

import com.ecl.ECLConfig;
import com.ecl.launcher.VersionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ServerJarDownloaderTest {
    @Test
    void resolvesInstalledVersionServerMetadataAndChannels(@TempDir Path tempDir) throws Exception {
        Field baseDir = ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        File previous = (File) baseDir.get(null);
        baseDir.set(null, tempDir.toFile());
        try {
            Path versionDir = tempDir.resolve("versions/1.21.4");
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve("1.21.4.json"), """
                    {
                      "id":"1.21.4",
                      "downloads":{"server":{
                        "url":"https://piston-data.mojang.com/v1/objects/hash/server.jar",
                        "sha1":"0123456789abcdef0123456789abcdef01234567",
                        "size":12345
                      }}
                    }
                    """);

            ServerJarDownloader.ServerArtifact artifact =
                    new ServerJarDownloader(new VersionManager()).resolve("1.21.4", null);

            assertEquals("1.21.4", artifact.versionId());
            assertEquals(12345, artifact.size());
            assertEquals(2, artifact.channels().size());
            assertFalse(artifact.channels().getFirst().mirror());
            assertEquals("BMCLAPI", artifact.channels().get(1).name());
        } finally {
            baseDir.set(null, previous);
        }
    }

    @Test
    void createsWindowsSafeSuggestedFileName() {
        assertEquals("minecraft_server.1.21_4_test.jar",
                ServerJarDownloader.suggestedFileName("1.21:4/test"));
    }
}
