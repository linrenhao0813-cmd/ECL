package com.ecl.launcher;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModLoaderInstallerTest {
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
