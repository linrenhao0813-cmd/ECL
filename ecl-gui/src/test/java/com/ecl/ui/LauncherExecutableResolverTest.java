package com.ecl.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LauncherExecutableResolverTest {
    @Test
    void doesNotTreatJavaOrJarAsPackagedLauncher(@TempDir Path root) throws Exception {
        Path java = Files.createFile(root.resolve("java.exe"));
        Path jar = Files.createFile(root.resolve("ecl-gui.jar"));

        assertNull(LauncherUI.resolveLauncherExecutableCandidate(
                "", java.toString(), root.resolve("working"), jar));
    }

    @Test
    void resolvesExplicitOrAdjacentPackagedLauncher(@TempDir Path root) throws Exception {
        Path explicit = Files.createFile(root.resolve("CustomEcl.exe"));
        assertEquals(explicit.toAbsolutePath().normalize(),
                LauncherUI.resolveLauncherExecutableCandidate(
                        explicit.toString(), "", root.resolve("working"), null));

        Path distribution = Files.createDirectories(root.resolve("distribution"));
        Path launcher = Files.createFile(distribution.resolve("ECL.exe"));
        Path jar = Files.createFile(distribution.resolve("ecl-gui.jar"));
        assertEquals(launcher.toAbsolutePath().normalize(),
                LauncherUI.resolveLauncherExecutableCandidate(
                        "", "", root.resolve("working"), jar));
    }
}
