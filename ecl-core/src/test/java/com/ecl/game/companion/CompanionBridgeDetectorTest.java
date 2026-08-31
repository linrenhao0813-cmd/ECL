package com.ecl.game.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionBridgeDetectorTest {
    @Test
    void detectsModAndProtocolFromFabricManifest(@TempDir Path instance) throws Exception {
        Path mods = Files.createDirectories(instance.resolve("mods"));
        writeManifest(mods.resolve("minecraft-ai-companion.jar"), """
                {"id":"minecraft-ai-companion","version":"0.3.0",
                 "custom":{"eclBridgeProtocol":1},
                 "depends":{"minecraft":"~26.1.2","fabricloader":">=0.19.3"}}
                """);

        CompanionBridgeState state = new CompanionBridgeDetector().detect(instance,
                "26.1.2", "0.19.3", false);
        assertEquals(CompanionBridgeState.Status.INSTALLED, state.status());
        assertEquals("0.3.0", state.modVersion());
    }

    @Test
    void rejectsWrongProtocolAndMinecraftVersion(@TempDir Path instance) throws Exception {
        Path mods = Files.createDirectories(instance.resolve("mods"));
        writeManifest(mods.resolve("minecraft-ai-companion.jar"), """
                {"id":"minecraft-ai-companion","version":"0.3.0",
                 "custom":{"eclBridgeProtocol":2},
                 "depends":{"minecraft":"~27.1.2","fabricloader":">=0.19.3"}}
                """);

        CompanionBridgeState state = new CompanionBridgeDetector().detect(instance,
                "26.1.2", "0.19.3", false);
        assertEquals(CompanionBridgeState.Status.INCOMPATIBLE, state.status());
    }

    private static void writeManifest(Path jar, String json) throws Exception {
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output)) {
            archive.putNextEntry(new JarEntry("fabric.mod.json"));
            archive.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
    }
}
