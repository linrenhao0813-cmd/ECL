package com.ecl.launch;

import com.ecl.auth.OfflineAuth;
import com.ecl.game.VersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLauncherTest {
    @TempDir
    Path temp;

    @Test
    void previewDoesNotCreateNativesOrDownloadRuntime() throws Exception {
        Path versions = Files.createDirectories(temp.resolve("versions"));
        Path version = Files.createDirectories(versions.resolve("test"));
        Files.writeString(version.resolve("test.json"), """
                {"id":"test","mainClass":"example.Main","javaVersion":{"majorVersion":21},
                 "arguments":{"game":["--accessToken","${auth_access_token}"]},"libraries":[]}
                """, StandardCharsets.UTF_8);
        Files.write(version.resolve("test.jar"), new byte[]{1});
        Path libraries = Files.createDirectories(temp.resolve("libraries"));
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Path game = temp.resolve("game");
        LaunchEnvironment environment = new LaunchEnvironment(versions.toFile(), libraries.toFile(),
                assets.toFile(), "ECL", "test");
        LaunchOptions options = LaunchOptions.builder()
                .versionId("test")
                .auth(new OfflineAuth("Player"))
                .gameDirectory(game.toFile())
                .environment(environment)
                .build();

        LaunchCommand command = new DefaultLauncher(new VersionRepository(versions.toFile()), environment)
                .preview(options);

        assertTrue(command.commandLine().contains("--accessToken"));
        assertFalse(Files.exists(environment.nativesDirectory("test").toPath()));
        assertFalse(Files.exists(game));
    }
}
