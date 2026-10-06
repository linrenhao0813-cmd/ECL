package com.ecl.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModLoaderInstallerTest {
    @Test
    void mapsLegacyAndYearBasedMinecraftVersionsToNeoForgePrefixes() throws Exception {
        assertEquals("20.4.", LoaderMetadataClient.neoForgePrefix("1.20.4"));
        assertEquals("21.1.", LoaderMetadataClient.neoForgePrefix("1.21.1"));
        assertEquals("26.1.0.", LoaderMetadataClient.neoForgePrefix("26.1"));
        assertEquals("26.1.2.", LoaderMetadataClient.neoForgePrefix("26.1.2"));
        assertEquals("26.2.0.", LoaderMetadataClient.neoForgePrefix("26.2"));
        assertThrows(IOException.class, () -> LoaderMetadataClient.neoForgePrefix("invalid"));
    }

    @Test
    void installerJavaUsesLocalMetadataBeforeVersionNameFallback(@TempDir Path temp) throws Exception {
        Path versions = Files.createDirectories(temp.resolve("versions"));
        Path base = Files.createDirectories(versions.resolve("1.21.1"));
        Files.writeString(base.resolve("1.21.1.json"),
                "{\"id\":\"1.21.1\",\"javaVersion\":{\"majorVersion\":25}}");
        ModLoaderInstaller installer = new ModLoaderInstaller(versions, temp.resolve("libraries"));

        assertEquals(25, installer.requiredJavaForMinecraft("1.21.1"));
        assertEquals(25, installer.requiredJavaForMinecraft("26.2"));
        assertEquals(17, installer.requiredJavaForMinecraft("1.20.1"));

        Files.writeString(base.resolve("1.21.1.json"), "broken metadata");
        assertThrows(IOException.class, () -> installer.requiredJavaForMinecraft("1.21.1"));
    }

    @Test
    void stableVersionsSortAheadOfPrereleases() {
        List<String> versions = new ArrayList<>(
                List.of("21.1.10-beta", "21.1.9", "21.1.10", "21.1.10-rc1"));

        versions.sort(ModLoaderInstaller::compareVersionsDescending);

        assertEquals(List.of("21.1.10", "21.1.9", "21.1.10-rc1", "21.1.10-beta"), versions);
    }

    @Test
    void usesLoaderSpecificHeadlessInstallerArgument() {
        assertEquals("--installClient",
                ModLoaderInstaller.installerArgument(ModLoaderInstaller.Loader.FORGE));
        assertEquals("--install-client",
                ModLoaderInstaller.installerArgument(ModLoaderInstaller.Loader.NEOFORGE));
    }
}
